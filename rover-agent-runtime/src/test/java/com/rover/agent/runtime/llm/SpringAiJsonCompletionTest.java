package com.rover.agent.runtime.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rover.agent.runtime.metrics.AgentMetrics;
import com.rover.agent.runtime.metrics.MicrometerAgentMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * 廉价调用加固：按场景超时收紧等待上限，且只为超时重试一次。
 *
 * 意图识别、调查规划这类"失败也能兜底"的小调用不该让用户等满配置里的 30 秒；
 * 但重试只对超时成立——鉴权、模型不存在这类失败重试一次结果也一样，白等。
 */
class SpringAiJsonCompletionTest {

    @Test
    void retriesOnceWithTheSameSceneTimeoutAfterATimeout() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(model.call(any(Prompt.class)))
                .thenThrow(new RuntimeException(new InterruptedIOException("timeout")))
                .thenReturn(response("{\"intent\":\"QUERY_STATE\"}"));
        RecordingGateway gateway = new RecordingGateway(model);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        Optional<String> answer = new SpringAiJsonCompletion(gateway, new MicrometerAgentMetrics(registry),
                "意图识别", 10).complete("系统提示", "用户消息");

        assertTrue(answer.isPresent());
        assertEquals("{\"intent\":\"QUERY_STATE\"}", answer.get());
        // 两次都用场景超时（10 秒），而不是配置里的 30 秒
        assertEquals(List.of(10, 10), gateway.requestedTimeouts);
        // 指标口径不变：重试算同一次调用，延迟是含重试的总耗时，且最终成功不记失败
        assertEquals(1L, registry.get("rover.agent.model.duration").timer().count());
        assertNull(registry.find("rover.agent.model.error").counter());
    }

    @Test
    void doesNotRetryWhenTheFailureIsNotATimeout() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenThrow(new RuntimeException("模型不存在"));
        RecordingGateway gateway = new RecordingGateway(model);

        Optional<String> answer = new SpringAiJsonCompletion(gateway, AgentMetrics.NOOP, "意图识别", 10)
                .complete("系统提示", "用户消息");

        assertTrue(answer.isEmpty());
        assertEquals(1, gateway.requestedTimeouts.size());
    }

    @Test
    void keepsTheConfiguredTimeoutAndDoesNotRetryWithoutASceneLimit() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenThrow(new RuntimeException(new InterruptedIOException("timeout")));
        RecordingGateway gateway = new RecordingGateway(model);

        Optional<String> answer = new SpringAiJsonCompletion(gateway, AgentMetrics.NOOP, "意图识别", 0)
                .complete("系统提示", "用户消息");

        assertTrue(answer.isEmpty());
        // 0 = 用模型配置里的超时；没有场景上限时不做重试
        assertEquals(List.of(0), gateway.requestedTimeouts);
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** 只记录"被请求了多大的超时"的模型端口替身，模型行为由 {@link ChatModel} 替身决定。 */
    private static final class RecordingGateway implements ChatModelGateway {

        private final ChatClient client;
        private final List<Integer> requestedTimeouts = new ArrayList<>();

        private RecordingGateway(ChatModel model) {
            this.client = ChatClient.builder(model).build();
        }

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public ChatClient chatClient() {
            return client;
        }

        @Override
        public ChatClient chatClient(int timeoutSeconds) {
            requestedTimeouts.add(timeoutSeconds);
            return client;
        }

        @Override
        public String description() {
            return "测试模型 @ local";
        }
    }
}