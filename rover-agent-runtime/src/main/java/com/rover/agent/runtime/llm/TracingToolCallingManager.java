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
 * 工具回调前按返回顺序绑定 toolCallId，执行后清除。
 * ponytail: 依赖串行工具调用；框架改为并行时须改为按调用绑定。
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
