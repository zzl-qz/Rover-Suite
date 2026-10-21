package com.rover.agent.runtime.llm;

import com.rover.agent.runtime.task.AgentRunBudget;
import java.util.List;
import java.util.function.Function;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 位于工具循环内部，每次模型请求（包括重试）都经过同一个任务预算。 */
public final class AgentBudgetAdvisor implements CallAdvisor, StreamAdvisor {

    private static final TokenCountEstimator TOKENS = new JTokkitTokenCountEstimator();
    private final AgentRunBudget budget;
    private final Function<ChatResponse, String> reasoning;
    private final ModelClient model;
    private final String scene;
    private final String promptVersion;

    private AgentBudgetAdvisor(AgentRunBudget budget, Function<ChatResponse, String> reasoning,ModelClient model,String scene,String promptVersion) {
        this.budget = budget;
        this.reasoning = reasoning;
        this.model=model;this.scene=scene;this.promptVersion=promptVersion;
    }

    public static ChatClient client(ChatClient client, AgentRunBudget budget,
                                    Function<ChatResponse, String> reasoning) {
        if (budget == null) {
            return client;
        }
        return client(new ModelClient(client,com.rover.agent.core.model.ModelIdentity.unknown(),null,null),budget,reasoning,"LEGACY","legacy-v1");
    }

    public static ChatClient client(ModelClient model,AgentRunBudget budget,Function<ChatResponse,String> reasoning,
                                    String scene,String promptVersion) {
        if (model==null || model.client()==null) { throw new IllegalStateException("模型客户端不可用"); }
        if (budget==null) { return model.client(); }
        AgentBudgetAdvisor advisor = new AgentBudgetAdvisor(budget, reasoning,model,scene,promptVersion);
        return model.client().mutate().defaultAdvisors(advisor.new ToolGuardAdvisor(), advisor).build();
    }

    @Override
    public String getName() {
        return "Agent 执行预算";
    }

    @Override
    public int getOrder() {
        return ToolCallingAdvisor.DEFAULT_ORDER + 1;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Round round = prepare(request);
        try {
            ChatClientResponse completedResponse=Mono.fromCallable(() -> chain.nextCall(round.request))
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnNext(response -> round.collect(response.chatResponse()))
                    .takeUntilOther(budget.stopSignal())
                    .doOnSuccess(result -> budget.check())
                    .block(budget.remaining());
            round.finish(null); return completedResponse;
        } catch (RuntimeException failure) {
            round.finish(failure);
            // 阻塞等待可能比任务定时器先到截止点；先结算预算，不能留下未标记的超时任务。
            budget.check();
            throw failure;
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            Round round = prepare(request);
            return chain.nextStream(round.request)
                    .doOnNext(response -> round.collect(response.chatResponse()))
                    .takeUntilOther(budget.stopSignal())
                    .doOnComplete(() -> { budget.check(); round.finish(null); })
                    .doOnError(round::finish)
                    .doOnCancel(() -> round.finish(new java.util.concurrent.CancellationException()));
        });
    }

    private Round prepare(ChatClientRequest request) {
        Prompt prompt = request.prompt();
        ChatOptions options = prompt.getOptions();
        long input = 0;
        StringBuilder promptAudit=new StringBuilder();
        StringBuilder toolAudit=new StringBuilder();
        for (Message message : prompt.getInstructions()) {
            promptAudit.append(message.getMessageType()).append(':').append(ModelContracts.digest(message.getText()));
            input += estimate(message.getText()) + 4L;
            if (message instanceof AssistantMessage assistant) {
                for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    input += estimate(call.name()) + estimate(call.arguments()) + 4L;
                    promptAudit.append(ModelContracts.digest(call.name(),call.arguments()));
                }
            } else if (message instanceof ToolResponseMessage tools) {
                for (ToolResponseMessage.ToolResponse response : tools.getResponses()) {
                    input += estimate(response.name()) + estimate(response.responseData()) + 4L;
                    promptAudit.append(ModelContracts.digest(response.name(),response.responseData()));
                }
            }
        }
        List<ToolCallback> callbacks = options instanceof ToolCallingChatOptions tools
                ? tools.getToolCallbacks() : List.of();
        if (callbacks == null) {
            callbacks = List.of();
        }
        for (ToolCallback callback : callbacks) {
            ToolDefinition definition = callback.getToolDefinition();
            input += estimate(definition.name()) + estimate(definition.description())
                    + estimate(definition.inputSchema()) + 4L;
            toolAudit.append(ModelContracts.digest(definition.name(),definition.description(),definition.inputSchema()));
        }
        String promptDigest=ModelContracts.digest(promptAudit.toString());
        String toolDigest=ModelContracts.digest(toolAudit.toString());
        ProviderIsolation.Lease lease=null;
        try {
            if (model.isolation()!=null) { lease=model.isolation().acquire(model.isolationKey()); }
        } catch (ProviderIsolation.Rejected rejected) {
            String id=budget.invocationStarted(scene,model.identity(),promptVersion,promptDigest,toolDigest,0,0);
            budget.invocationFinished(id,rejected.kind(),0,0,null,null); throw rejected;
        }
        int outputLimit;
        String invocationId;
        try {
            var reservation=budget.beginModelInvocation(scene,model.identity(),promptVersion,promptDigest,toolDigest,input,options==null ? null : options.getMaxTokens());
            outputLimit=reservation.outputLimit();invocationId=reservation.id();
        } catch (RuntimeException failure) { if (lease!=null) { lease.close(); } throw failure; }
        ChatOptions bounded;
        if (options instanceof ToolCallingChatOptions tools) {
            bounded = tools.mutate().maxTokens(outputLimit).build();
        } else {
            bounded = (options == null ? ChatOptions.builder() : options.mutate()).maxTokens(outputLimit).build();
        }
        return new Round(request.mutate().prompt(new Prompt(prompt.getInstructions(), bounded)).build(),
                input, outputLimit,invocationId,lease);
    }

    private static int estimate(String text) {
        return text == null || text.isEmpty() ? 0 : TOKENS.estimate(text);
    }

    /** 工具循环保留初始 options；在进入循环前包装，才能保护实际执行的回调。 */
    private final class ToolGuardAdvisor implements CallAdvisor, StreamAdvisor {
        @Override public String getName() { return "Agent 工具执行保护"; }
        @Override public int getOrder() { return ToolCallingAdvisor.DEFAULT_ORDER - 1; }

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            return chain.nextCall(guard(request));
        }

        @Override
        public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
            return Flux.defer(() -> chain.nextStream(guard(request)))
                    .takeUntilOther(budget.stopSignal())
                    .doOnComplete(budget::check);
        }

        private ChatClientRequest guard(ChatClientRequest request) {
            budget.check();
            Prompt prompt = request.prompt();
            if (!(prompt.getOptions() instanceof ToolCallingChatOptions tools)
                    || tools.getToolCallbacks() == null || tools.getToolCallbacks().isEmpty()) {
                return request;
            }
            List<ToolCallback> callbacks = tools.getToolCallbacks().stream()
                    .map(callback -> (ToolCallback) new GuardedCallback(callback)).toList();
            return request.mutate().prompt(new Prompt(prompt.getInstructions(),
                    tools.mutate().toolCallbacks(callbacks).build())).build();
        }
    }

    private final class Round {
        private final ChatClientRequest request;
        private final int outputLimit;
        private final StringBuilder generated = new StringBuilder();
        private long chargedInput;
        private long chargedOutput;
        private final String invocationId;
        private final ProviderIsolation.Lease lease;
        private String returnedModel;
        private String fingerprint;
        private boolean finished;

        private Round(ChatClientRequest request, long input, int outputLimit,String invocationId,ProviderIsolation.Lease lease) {
            this.request = request;
            this.chargedInput = input;
            this.outputLimit = outputLimit;
            this.invocationId=invocationId;this.lease=lease;
        }

        private synchronized void finish(Throwable failure) {
            if (finished) { return; } finished=true;
            if (lease!=null) { if (failure==null) { lease.success(); } else { lease.failure(failure); } }
            budget.modelInvocationCompleted(invocationId,failure==null ? "SUCCEEDED" : ModelFailures.kind(failure),chargedInput,chargedOutput,returnedModel,fingerprint);
        }

        private void collect(ChatResponse response) {
            budget.check();
            if (response == null) {
                return;
            }
            if (response.getMetadata()!=null) {
                if (response.getMetadata().getModel()!=null && !response.getMetadata().getModel().isBlank()) { returnedModel=response.getMetadata().getModel(); }
                Object value=response.getMetadata().get("systemFingerprint");
                if (value==null) { value=response.getMetadata().get("system_fingerprint"); }
                if (value instanceof String text) { fingerprint=text; }
            }
            if (response.getResult() != null) {
                AssistantMessage message = response.getResult().getOutput();
                if (message != null) {
                    append(message.getText());
                    for (AssistantMessage.ToolCall call : message.getToolCalls()) {
                        append(call.name());
                        append(call.arguments());
                    }
                }
            }
            append(reasoning.apply(response));
            long input = chargedInput;
            long output = Math.max(chargedOutput, estimate(generated.toString()));
            if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                var usage = response.getMetadata().getUsage();
                if (usage.getPromptTokens() != null) {
                    input = Math.max(input, usage.getPromptTokens());
                }
                if (usage.getCompletionTokens() != null) {
                    output = Math.max(output, usage.getCompletionTokens());
                }
            }
            budget.consumeModelTokens(input-chargedInput,output-chargedOutput);
            chargedInput = input;
            chargedOutput = output;
            if (output > outputLimit) {
                throw budget.stop("单次模型输出已达 token 上限（" + outputLimit + "），任务已停止");
            }
        }

        private void append(String text) {
            if (text != null) {
                generated.append(text);
            }
        }
    }

    private final class GuardedCallback implements ToolCallback {
        private final ToolCallback delegate;

        private GuardedCallback(ToolCallback delegate) {
            this.delegate = delegate;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String input) {
            return call(input, null);
        }

        @Override
        public String call(String input, ToolContext context) {
            budget.check();
            String result = delegate.call(input, context);
            budget.toolResult(getToolDefinition().name(), input, result);
            return result;
        }
    }
}
