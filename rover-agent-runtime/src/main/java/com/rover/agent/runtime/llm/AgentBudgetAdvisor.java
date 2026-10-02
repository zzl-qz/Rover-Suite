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

    private AgentBudgetAdvisor(AgentRunBudget budget, Function<ChatResponse, String> reasoning) {
        this.budget = budget;
        this.reasoning = reasoning;
    }

    public static ChatClient client(ChatClient client, AgentRunBudget budget,
                                    Function<ChatResponse, String> reasoning) {
        if (budget == null) {
            return client;
        }
        AgentBudgetAdvisor advisor = new AgentBudgetAdvisor(budget, reasoning);
        return client.mutate().defaultAdvisors(advisor.new ToolGuardAdvisor(), advisor).build();
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
        return Mono.fromCallable(() -> chain.nextCall(round.request))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(response -> round.collect(response.chatResponse()))
                .takeUntilOther(budget.stopSignal())
                .doOnSuccess(response -> budget.check())
                .block(budget.remaining());
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            Round round = prepare(request);
            return chain.nextStream(round.request)
                    .doOnNext(response -> round.collect(response.chatResponse()))
                    .takeUntilOther(budget.stopSignal())
                    .doOnComplete(budget::check);
        });
    }

    private Round prepare(ChatClientRequest request) {
        Prompt prompt = request.prompt();
        ChatOptions options = prompt.getOptions();
        long input = 0;
        for (Message message : prompt.getInstructions()) {
            input += estimate(message.getText()) + 4L;
            if (message instanceof AssistantMessage assistant) {
                for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    input += estimate(call.name()) + estimate(call.arguments()) + 4L;
                }
            } else if (message instanceof ToolResponseMessage tools) {
                for (ToolResponseMessage.ToolResponse response : tools.getResponses()) {
                    input += estimate(response.name()) + estimate(response.responseData()) + 4L;
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
        }
        int outputLimit = budget.beginModelCall(input, options == null ? null : options.getMaxTokens());
        ChatOptions bounded;
        if (options instanceof ToolCallingChatOptions tools) {
            bounded = tools.mutate().maxTokens(outputLimit).build();
        } else {
            bounded = (options == null ? ChatOptions.builder() : options.mutate()).maxTokens(outputLimit).build();
        }
        return new Round(request.mutate().prompt(new Prompt(prompt.getInstructions(), bounded)).build(),
                input, outputLimit);
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

        private Round(ChatClientRequest request, long input, int outputLimit) {
            this.request = request;
            this.chargedInput = input;
            this.outputLimit = outputLimit;
        }

        private void collect(ChatResponse response) {
            budget.check();
            if (response == null) {
                return;
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
            budget.consume(input - chargedInput + output - chargedOutput);
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
