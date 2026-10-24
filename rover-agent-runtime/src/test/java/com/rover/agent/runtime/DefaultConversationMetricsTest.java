package com.rover.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rover.agent.core.capability.CapabilityExecutor;
import com.rover.agent.core.model.TaskStatus;
import com.rover.agent.runtime.llm.ChatModelGateway;
import com.rover.agent.runtime.llm.ConversationModel;
import com.rover.agent.runtime.metrics.MicrometerAgentMetrics;
import com.rover.agent.runtime.task.InvestigationTaskRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import reactor.core.publisher.Flux;

/** 验证宿主默认装配的对话实际写指标，避免只有显式传 metrics 的模型才可观测。 */
class DefaultConversationMetricsTest {

    @Test
    void conversationClockHasAnOffsetToCompareWithUtcEvidence() {
        ChatModelGateway gateway = mock(ChatModelGateway.class);
        when(gateway.available()).thenReturn(true);
        AtomicReference<String> received = new AtomicReference<>();
        ToolLoopService service = new ToolLoopService(mock(CapabilityExecutor.class), gateway,
                (system, user, tools, delta, thinking) -> { received.set(user); return "ok"; });
        InvestigationTaskRegistry tasks = new InvestigationTaskRegistry();
        try {
            service.run(tasks.register("clock-session", "请比较当前时间和 UTC 告警时间"), "");
            String clock = received.get().lines().filter(line -> line.startsWith("【当前时间】"))
                    .findFirst().orElseThrow().substring("【当前时间】".length());
            Instant timestamp = OffsetDateTime.parse(clock).toInstant();
            assertTrue(Duration.between(timestamp, Instant.now()).abs().getSeconds() < 30);
        } finally {
            tasks.stop();
        }
    }

    @Test
    void defaultModelRecordsConversationOutcomeThroughTheConfiguredRegistry() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("你好"))))));
        ChatModelGateway gateway = mock(ChatModelGateway.class);
        when(gateway.available()).thenReturn(true);
        when(gateway.chatClient()).thenReturn(ChatClient.builder(model).build());
        when(gateway.select(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyBoolean())).thenCallRealMethod();
        when(gateway.description()).thenReturn("fixture-model @ localhost");
        when(gateway.reasoningDelta(any(ChatResponse.class))).thenReturn("");
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("gateway", gateway);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ToolLoopService service = new AgentRuntimeConfiguration().agentToolLoopService(
                mock(CapabilityExecutor.class), beans.getBeanProvider(ChatModelGateway.class),
                beans.getBeanProvider(ConversationModel.class), null, null, new MicrometerAgentMetrics(meters));
        InvestigationTaskRegistry tasks = new InvestigationTaskRegistry();
        try {
            var task = tasks.register("metrics-session", "你好");
            service.run(task, "");
            assertEquals(TaskStatus.COMPLETED, task.view().status());
            assertEquals("你好", task.view().result().summary());
            assertEquals(1.0, meters.get("rover.agent.model.calls").tag("scene", "对话")
                    .tag("outcome", "ok").counter().count());
        } finally {
            tasks.stop();
            meters.close();
        }
    }
}
