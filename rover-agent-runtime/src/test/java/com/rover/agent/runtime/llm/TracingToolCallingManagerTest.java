package com.rover.agent.runtime.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rover.agent.core.capability.ToolCallTrace;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 工具执行期间，证据层要能读到模型这次返回的调用号，并且离开回调后不再残留。
 */
class TracingToolCallingManagerTest {

    @Test
    void bindsTheModelToolCallIdOnlyWhileTheCallbackRuns() {
        AtomicReference<String> seen = new AtomicReference<>("missing");
        ToolCallback callback = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("queryLogs").description("日志").inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                seen.set(ToolCallTrace.current());
                return "ok";
            }
        };
        ToolCallingManager delegate = new ToolCallingManager() {
            @Override
            public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
                return List.of();
            }

            @Override
            public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
                ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
                options.getToolCallbacks().get(0).call("{}");
                return ToolExecutionResult.builder().conversationHistory(List.of()).build();
            }
        };
        Prompt prompt = new Prompt(List.of(new UserMessage("查日志")),
                DefaultToolCallingChatOptions.builder().toolCallbacks(callback).build());
        AssistantMessage assistant = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-9", "function", "queryLogs", "{}")))
                .build();

        new TracingToolCallingManager(delegate).executeToolCalls(prompt, new ChatResponse(List.of(new Generation(assistant))));

        assertEquals("call-9", seen.get());
        assertEquals("", ToolCallTrace.current());
    }
}
