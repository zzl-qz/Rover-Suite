package com.rover.agent.runtime.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** 任务独占的预算；响应流和工具线程显式持有它，ThreadLocal 只用于 Worker 构建模型请求。 */
public final class AgentRunBudget {

    private static final ThreadLocal<AgentRunBudget> CURRENT = new ThreadLocal<>();
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AgentRunLimits limits;
    private final Map<ToolRequest, RepeatedResult> toolResults = new HashMap<>();
    private final Sinks.One<String> stopped = Sinks.one();
    private long deadline;
    private int modelCalls;
    private long tokens;
    private long deadlineAtMillis;
    private int pendingOutput;
    private int toolCalls;
    private java.util.function.Consumer<State> stateSink = state -> { };
    private Runnable attemptGuard = () -> { };
    public synchronized void validateWith(Runnable guard) { attemptGuard = guard; }
    private volatile String stopReason;
    private final java.util.List<com.rover.agent.core.model.ModelInvocation> invocations = new java.util.ArrayList<>();
    private final java.util.Set<String> queryTemplates = new java.util.TreeSet<>();

    public record State(int modelCalls, long tokens, int pendingOutput, int toolCalls, long deadlineAtMillis,
                        int maxModelCalls, int maxOutputTokens, long maxTotalTokens, long timeoutMillis, int maxRepeatedToolResults,
                        java.util.List<com.rover.agent.core.model.ModelInvocation> modelInvocations, java.util.List<String> queryTemplateVersions) {
        public State {
            modelInvocations = modelInvocations == null ? java.util.List.of() : java.util.List.copyOf(modelInvocations);
            queryTemplateVersions = queryTemplateVersions == null ? java.util.List.of() : java.util.List.copyOf(queryTemplateVersions);
        }
        public State(int modelCalls,long tokens,int pendingOutput,int toolCalls,long deadlineAtMillis,int maxModelCalls,
                     int maxOutputTokens,long maxTotalTokens,long timeoutMillis,int maxRepeatedToolResults) {
            this(modelCalls,tokens,pendingOutput,toolCalls,deadlineAtMillis,maxModelCalls,maxOutputTokens,maxTotalTokens,
                    timeoutMillis,maxRepeatedToolResults,java.util.List.of(),java.util.List.of());
        }
    }

    public synchronized State snapshot() {
        return new State(modelCalls, tokens, pendingOutput, toolCalls, deadlineAtMillis, limits.maxModelCalls(),
                limits.maxOutputTokens(), limits.maxTotalTokens(), limits.taskTimeout().toMillis(), limits.maxRepeatedToolResults(),
                java.util.List.copyOf(invocations),java.util.List.copyOf(queryTemplates));
    }

    public synchronized void persistTo(java.util.function.Consumer<State> sink) { this.stateSink = sink; }

    public synchronized void restore(State state) {
        modelCalls = state.modelCalls();
        // 中断调用的输出无法精确计费，按已预留上限计入，不能借重启获得新额度。
        tokens = state.tokens() + state.pendingOutput(); toolCalls = state.toolCalls();
        deadlineAtMillis = state.deadlineAtMillis();
        invocations.clear();
        state.modelInvocations().forEach(call -> invocations.add("STARTED".equals(call.status())
                ? call.finish("INTERRUPTED",call.accountedInputTokens(),call.outputLimit(),null,null) : call));
        queryTemplates.clear(); queryTemplates.addAll(state.queryTemplateVersions());
        if (deadlineAtMillis > 0) {
            deadline = System.nanoTime() + Math.max(0, deadlineAtMillis - System.currentTimeMillis()) * 1_000_000L;
        }
    }

    public synchronized java.util.List<com.rover.agent.core.model.ModelInvocation> modelInvocations() { return java.util.List.copyOf(invocations); }

    public synchronized void observeTemplates(java.util.List<com.rover.agent.core.model.Evidence> evidence) {
        int before=queryTemplates.size();
        if (evidence != null) { evidence.forEach(e -> { if (e.envelope().queryTemplateVersion() != null) { queryTemplates.add(e.envelope().queryTemplateVersion()); } }); }
        if (queryTemplates.size()!=before) { stateSink.accept(snapshot()); }
    }

    public synchronized String invocationStarted(String scene, com.rover.agent.core.model.ModelIdentity identity,
            String promptVersion,String promptDigest,String toolsDigest,long input,int output) {
        check();
        if (invocations.size() >= 60) { throw stop("模型调用审计已达上限（60 条）"); }
        String id = java.util.UUID.randomUUID().toString();
        invocations.add(new com.rover.agent.core.model.ModelInvocation(id,scene,identity,promptVersion,
                com.rover.agent.runtime.llm.ModelContracts.POLICY,promptDigest,toolsDigest,java.util.List.copyOf(queryTemplates),
                System.currentTimeMillis(),null,"STARTED","NOT_EVALUATED",input,0,output,null,null));
        stateSink.accept(snapshot()); return id;
    }

    public record Reservation(String id,int outputLimit) { }
    public synchronized Reservation beginModelInvocation(String scene,com.rover.agent.core.model.ModelIdentity identity,
            String promptVersion,String promptDigest,String toolsDigest,long input,Integer requestedOutput) {
        check();
        if (invocations.size()>=60) { throw stop("模型调用审计已达上限（60 条）"); }
        int output=beginModelCall(input,requestedOutput,false);
        String id=invocationStarted(scene,identity,promptVersion,promptDigest,toolsDigest,input,output);
        return new Reservation(id,output);
    }

    public synchronized void invocationFinished(String id,String status,long input,long output,String model,String fingerprint) {
        for (int i=0;i<invocations.size();i++) {
            var call=invocations.get(i);
            if (call.id().equals(id) && "STARTED".equals(call.status())) {
                invocations.set(i,call.finish(status,input,output,model,fingerprint)); stateSink.accept(snapshot()); return;
            }
        }
    }

    public synchronized void validation(String scene,boolean accepted) {
        for (int i=invocations.size()-1;i>=0;i--) {
            var call=invocations.get(i);
            if (call.scene().equals(scene) && "SUCCEEDED".equals(call.status())) {
                invocations.set(i,call.validated(accepted ? "ACCEPTED" : "REJECTED")); stateSink.accept(snapshot()); return;
            }
        }
    }

    public synchronized void reserveTool() {
        check();
        if (toolCalls >= 30) { throw stop("任务累计只读工具调用已达上限（30 次），不会因重启重置"); }
        toolCalls++; stateSink.accept(snapshot());
    }

    public synchronized void modelCallCompleted() { pendingOutput = 0; stateSink.accept(snapshot()); }
    /** 响应结算与调用终态一次落盘，不能在两次写入之间留下已退预算、调用却未完成的窗口。 */
    public synchronized void modelInvocationCompleted(String id,String status,long input,long output,String model,String fingerprint) {
        for (int i=0;i<invocations.size();i++) {
            var call=invocations.get(i);
            if (call.id().equals(id) && "STARTED".equals(call.status())) {
                if (!"SUCCEEDED".equals(status)) { tokens+=pendingOutput;output+=pendingOutput; }
                pendingOutput=0;
                invocations.set(i,call.finish(status,input,output,model,fingerprint)); stateSink.accept(snapshot()); return;
            }
        }
    }

    public AgentRunBudget(AgentRunLimits limits) {
        this.limits = limits;
    }

    public static AgentRunBudget current() {
        return CURRENT.get();
    }

    void enter() {
        start();
        CURRENT.set(this);
    }

    void leave() {
        if (CURRENT.get() == this) {
            CURRENT.remove();
        }
    }

    public AgentRunLimits limits() {
        return limits;
    }

    public synchronized void start() {
        if (deadline == 0) {
            deadline = System.nanoTime() + limits.taskTimeout().toNanos();
            deadlineAtMillis = System.currentTimeMillis() + limits.taskTimeout().toMillis();
            stateSink.accept(snapshot());
        }
    }

    public synchronized Duration remaining() {
        check();
        return Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
    }

    public synchronized void check() {
        attemptGuard.run();
        start();
        if (stopReason != null) {
            throw new LimitExceeded(stopReason);
        }
        if (System.nanoTime() - deadline >= 0) {
            throw stop(timeoutReason());
        }
    }

    /** 调用前计入输入估算，并按剩余总预算收紧本轮输出上限。 */
    public synchronized int beginModelCall(long inputTokens, Integer requestedOutput) {
        return beginModelCall(inputTokens,requestedOutput,true);
    }

    private int beginModelCall(long inputTokens,Integer requestedOutput,boolean persist) {
        check();
        if (pendingOutput > 0) { tokens += pendingOutput; pendingOutput = 0; }
        if (modelCalls >= limits.maxModelCalls()) {
            throw stop("模型调用次数已达上限（" + limits.maxModelCalls() + " 次），任务已停止");
        }
        long available = limits.maxTotalTokens() - tokens - inputTokens;
        if (available <= 0) {
            throw stop("累计 token 预算已达上限（" + limits.maxTotalTokens() + "），任务已停止");
        }
        modelCalls++;
        tokens += inputTokens;
        int output = requestedOutput == null || requestedOutput <= 0
                ? limits.maxOutputTokens() : Math.min(requestedOutput, limits.maxOutputTokens());
        pendingOutput = (int) Math.min(output, available);
        if (persist) { stateSink.accept(snapshot()); }
        return pendingOutput;
    }

    /** 流式用量按增量结算；服务端没有 usage 时使用本地 tokenizer 估算。 */
    public synchronized void consume(long additionalTokens) {
        consumeModelTokens(0,additionalTokens);
    }

    /** 输入修正不能吃掉输出预留，否则流式中断会少算仍可能产生的输出。 */
    public synchronized void consumeModelTokens(long additionalInput,long additionalOutput) {
        check();
        tokens += Math.max(0,additionalInput)+Math.max(0,additionalOutput);
        pendingOutput = (int) Math.max(0, pendingOutput - Math.max(0, additionalOutput));
        stateSink.accept(snapshot());
        if (tokens > limits.maxTotalTokens()) {
            throw stop("累计 token 预算已达上限（" + limits.maxTotalTokens() + "），任务已停止");
        }
    }

    public synchronized void toolResult(String name, String arguments, String result) {
        check();
        Object input;
        try {
            JsonNode parsed = JSON.readTree(arguments);
            input = parsed == null ? arguments : parsed;
        } catch (Exception ex) {
            input = arguments;
        }
        ToolRequest key = new ToolRequest(name, input);
        RepeatedResult previous = toolResults.get(key);
        int count = previous != null && java.util.Objects.equals(previous.result(), result)
                ? previous.count() + 1 : 1;
        toolResults.put(key, new RepeatedResult(result, count));
        if (count >= limits.maxRepeatedToolResults()) {
            throw stop("工具 " + name + " 使用相同参数连续返回相同结果 " + count + " 次，没有取得新信息，任务已停止");
        }
    }

    public String timeoutReason() {
        return "任务超过总执行时限（" + limits.taskTimeout().toMillis() + " 毫秒），已停止执行";
    }

    public LimitExceeded stop(String reason) {
        synchronized (this) {
            if (stopReason == null) {
                stopReason = reason;
                for (int i=0;i<invocations.size();i++) {
                    var call=invocations.get(i);
                    if ("STARTED".equals(call.status())) { invocations.set(i,call.finish("CANCELLED",call.accountedInputTokens(),call.outputLimit(),null,null)); }
                }
                stateSink.accept(snapshot());
            }
        }
        stopped.tryEmitValue(stopReason);
        return new LimitExceeded(stopReason);
    }

    public String stopReason() {
        return stopReason;
    }

    /** takeUntilOther 收到值才会取消上游；调用方随后 check，把停止转换为任务异常。 */
    public Mono<String> stopSignal() {
        return stopped.asMono();
    }

    public static final class LimitExceeded extends RuntimeException {
        public LimitExceeded(String message) {
            super(message);
        }
    }

    private record ToolRequest(String name, Object arguments) { }
    private record RepeatedResult(String result, int count) { }
}
