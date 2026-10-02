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
    private volatile String stopReason;

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
        }
    }

    public synchronized Duration remaining() {
        check();
        return Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
    }

    public synchronized void check() {
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
        check();
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
        return (int) Math.min(output, available);
    }

    /** 流式用量按增量结算；服务端没有 usage 时使用本地 tokenizer 估算。 */
    public synchronized void consume(long additionalTokens) {
        check();
        tokens += Math.max(0, additionalTokens);
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
