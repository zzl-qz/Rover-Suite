package com.rover.agent.runtime.llm;

import com.rover.agent.core.capability.ToolCallTrace;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 在真正执行工具之前，把模型这次返回的 toolCallId 绑到当前线程。
 *
 * <p>Spring AI 的默认管理器不会把调用号放进 {@link ToolContext}，证据层又不能直接看见模型消息。
 * 这里按返回顺序包一层回调：框架每执行一个工具，就绑上对应的调用号，执行完立刻清掉。
 *
 * <p>ponytail: 假设同一次返回里的工具是串行执行的。框架如果改成并行调用，调用号会串。
 */
public final class TracingToolCallingManager implements ToolCallingManager {

    private final ToolCallingManager delegate;

    public TracingToolCallingManager(ToolCallingManager delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        List<String> ids = idsOf(chatResponse);
        if (ids.isEmpty() || !(prompt.getOptions() instanceof ToolCallingChatOptions options)) {
            return delegate.executeToolCalls(prompt, chatResponse);
        }
        List<ToolCallback> callbacks = options.getToolCallbacks();
        if (callbacks == null || callbacks.isEmpty()) {
            return delegate.executeToolCalls(prompt, chatResponse);
        }
        List<ToolCallback> wrapped = new ArrayList<>(callbacks.size());
        AtomicInteger cursor = new AtomicInteger();
        for (ToolCallback callback : callbacks) {
            wrapped.add(new BoundCallback(callback, ids, cursor));
        }
        Prompt traced = new Prompt(prompt.getInstructions(), options.mutate().toolCallbacks(wrapped).build());
        return delegate.executeToolCalls(traced, chatResponse);
    }

    private static List<String> idsOf(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return List.of();
        }
        List<AssistantMessage.ToolCall> calls = response.getResult().getOutput().getToolCalls();
        if (calls == null || calls.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(calls.size());
        for (AssistantMessage.ToolCall call : calls) {
            ids.add(call.id() == null ? "" : call.id());
        }
        return ids;
    }

    /** 按模型返回的顺序取调用号。取完之后再有调用就不再绑定，避免把上一个号借给下一次。 */
    private static final class BoundCallback implements ToolCallback {

        private final ToolCallback delegate;
        private final List<String> ids;
        private final AtomicInteger cursor;

        private BoundCallback(ToolCallback delegate, List<String> ids, AtomicInteger cursor) {
            this.delegate = delegate;
            this.ids = ids;
            this.cursor = cursor;
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
        public String call(String toolInput) {
            return inTrace(() -> delegate.call(toolInput));
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return inTrace(() -> delegate.call(toolInput, toolContext));
        }

        private String inTrace(java.util.function.Supplier<String> call) {
            int index = cursor.getAndIncrement();
            String id = index < ids.size() ? ids.get(index) : "";
            ToolCallTrace.bind(id);
            try {
                return call.get();
            } finally {
                ToolCallTrace.clear();
            }
        }
    }
}
