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
import java.util.function.Function;
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
 * 另一半是失败分类：每次调用都要能把结局记成可数的原因，否则「模型到底帮上忙没有」无从判断。
 */
class SpringAiJsonCompletionTest {

    /** 测试用契约：原样接受任何文本（这些用例只关心调用与重试行为，不关心契约内容）。 */
    private static final Function<String, Optional<String>> ACCEPT_ANY = Optional::of;

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
                "意图识别", 10).complete("系统提示", "用户消息", ACCEPT_ANY);

        assertTrue(answer.isPresent());
        assertEquals("{\"intent\":\"QUERY_STATE\"}", answer.get());
        // 两次都用场景超时（10 秒），而不是配置里的 30 秒
        assertEquals(List.of(10, 10), gateway.requestedTimeouts);
        // 指标口径不变：重试算同一次调用，延迟是含重试的总耗时，最终成功只记一条 ok
        assertEquals(1L, registry.get("rover.agent.model.duration").timer().count());
        assertEquals(1.0, registry.get("rover.agent.model.calls")
                .tag("outcome", "ok").tag("scene", "意图识别").counter().count());
    }

    @Test
    void doesNotRetryWhenTheFailureIsNotATimeout() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenThrow(new RuntimeException("模型不存在"));
        RecordingGateway gateway = new RecordingGateway(model);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        Optional<String> answer = new SpringAiJsonCompletion(gateway, new MicrometerAgentMetrics(registry),
                "意图识别", 10).complete("系统提示", "用户消息", ACCEPT_ANY);

        assertTrue(answer.isEmpty());
        assertEquals(1, gateway.requestedTimeouts.size());
        // 非超时失败归 error，与超时分开：前者重试无用，后者才值得重试
        assertEquals(1.0, registry.get("rover.agent.model.calls")
                .tag("outcome", "error").counter().count());
    }

    @Test
    void keepsTheConfiguredTimeoutAndDoesNotRetryWithoutASceneLimit() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenThrow(new RuntimeException(new InterruptedIOException("timeout")));
        RecordingGateway gateway = new RecordingGateway(model);

        Optional<String> answer = new SpringAiJsonCompletion(gateway, AgentMetrics.NOOP, "意图识别", 0)
                .complete("系统提示", "用户消息", ACCEPT_ANY);

        assertTrue(answer.isEmpty());
        // 0 = 用模型配置里的超时；没有场景上限时不做重试
        assertEquals(List.of(0), gateway.requestedTimeouts);
    }

    /** 拿到了内容但契约不认：这是提示词与契约的问题，必须与「没配模型 / 超时 / 报错」分开记。 */
    @Test
    void rejectedOutputIsCountedSeparatelyFromTransportFailures() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(response("{\"intent\":\"MAKE_COFFEE\"}"));
        RecordingGateway gateway = new RecordingGateway(model);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        Optional<String> answer = new SpringAiJsonCompletion(gateway, new MicrometerAgentMetrics(registry),
                "意图识别", 10).complete("系统提示", "用户消息", raw -> Optional.empty());

        assertTrue(answer.isEmpty());
        assertEquals(1.0, registry.get("rover.agent.model.calls")
                .tag("outcome", "rejected").counter().count());
        assertNull(registry.find("rover.agent.model.calls").tag("outcome", "ok").counter());
    }

    /** 未配置模型是纯规则部署的常态：不调用、不刷警告，但要在指标里留痕，免得被当成「模型从没被调用过」。 */
    @Test
    void notConfiguredIsRecordedWithoutCallingTheModel() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        RecordingGateway gateway = new RecordingGateway(model) {
            @Override
            public boolean configured() {
                return false;
            }
        };
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        Optional<String> answer = new SpringAiJsonCompletion(gateway, new MicrometerAgentMetrics(registry),
                "意图识别", 10).complete("系统提示", "用户消息", ACCEPT_ANY);

        assertTrue(answer.isEmpty());
        assertTrue(gateway.requestedTimeouts.isEmpty());
        assertEquals(1.0, registry.get("rover.agent.model.calls")
                .tag("outcome", "not_configured").counter().count());
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** 只记录"被请求了多大的超时"的模型端口替身，模型行为由 {@link ChatModel} 替身决定。 */
    private static class RecordingGateway implements ChatModelGateway {

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