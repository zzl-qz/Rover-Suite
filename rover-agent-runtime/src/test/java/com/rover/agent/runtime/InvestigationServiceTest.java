package com.rover.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rover.agent.core.event.TaskEvent;
import com.rover.agent.core.event.TaskEventSubscriber;
import com.rover.agent.core.event.TaskSnapshot;
import com.rover.agent.core.model.Hypothesis;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.port.ConfigReadPort;
import com.rover.agent.core.port.EventReadPort;
import com.rover.agent.core.port.InstanceReadPort;
import com.rover.agent.core.port.MetricReadPort;
import com.rover.agent.core.port.RouteReadPort;
import com.rover.agent.core.port.TraceReadPort;
import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.core.snapshot.GatewayMetricSnapshot;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import com.rover.agent.core.snapshot.TraceRow;
import com.rover.agent.core.snapshot.TraceSnapshot;
import com.rover.agent.runtime.llm.ChatModelGateway;
import com.rover.agent.runtime.llm.ModelExplainer;
import com.rover.agent.runtime.metrics.MicrometerAgentMetrics;
import com.rover.agent.runtime.task.IncidentRegistry;
import com.rover.agent.runtime.task.InvestigationTaskRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

/** 调查用例的场景级验证：只读端口输入 → 步骤、证据、假设结论与降级行为。 */
class InvestigationServiceTest {

    private RouteReadPort routes;
    private InstanceReadPort instances;
    private MetricReadPort metrics;
    private TraceReadPort traces;
    private ConfigReadPort configs;
    private EventReadPort events;
    private IncidentRegistry incidentRegistry;
    private InvestigationTaskRegistry taskRegistry;
    private InvestigationService investigations;

    @BeforeEach
    void setup() {
        routes = mock(RouteReadPort.class);
        instances = mock(InstanceReadPort.class);
        metrics = mock(MetricReadPort.class);
        traces = mock(TraceReadPort.class);
        configs = () -> List.of();
        events = () -> List.of();
        incidentRegistry = new IncidentRegistry();
        taskRegistry = new InvestigationTaskRegistry();
        investigations = new InvestigationService(routes, instances, metrics, traces, configs, events,
                incidentRegistry, taskRegistry, new ModelExplainer(TestGateway.notConfigured()));
        when(routes.discoveryMode()).thenReturn(DiscoveryMode.NAMESERVER);
        when(metrics.gatewayWindow(anyInt())).thenReturn(new GatewayMetricSnapshot(0, 0, 0, 1L));
        when(traces.byPath(anyString())).thenReturn(new TraceSnapshot(true, 1.0, List.of(), 1L));
    }

    @AfterEach
    void cleanup() {
        taskRegistry.stop();
    }

    @Test
    void explainsMismatchedServiceAndGroupAndKeepsTheIncidentRelation() throws Exception {
        when(routes.routes()).thenReturn(List.of(
                route("/api", "demo-service", ""), route("/api/demo/tt", "demo", "11")));
        when(instances.instances()).thenReturn(List.of(new InstanceSnapshot("demo-service", "", "", "127.0.0.1", 8081, true, 100, true, 0L)));

        TaskView task = await(investigations.submit("/api/demo/tt", "为什么失败？").taskId());

        assertEquals("COMPLETED", task.status().name());
        assertEquals("MEDIUM", task.result().confidence().name());
        assertTrue(task.result().summary().contains("demo / 11"));
        assertTrue(task.result().evidence().stream().anyMatch(item -> item.rawReference().equals("/api/routes")));
        assertTrue(task.result().evidence().stream().anyMatch(item -> item.rawReference().equals("/api/instances")));
        // 提问会开启会话与事件，任务挂在事件下：连续追问与处置复核才有聚合点。
        assertNotNull(task.sessionId());
        assertNotNull(task.incidentId());
        assertTrue(incidentRegistry.incident(task.incidentId()).orElseThrow().taskIds().contains(task.taskId()));
        assertEquals(task.sessionId(), incidentRegistry.incident(task.incidentId()).orElseThrow().sessionId());
        assertTrue(incidentRegistry.session(task.sessionId()).isPresent());
        // 结论产出后回写事件：摘要与状态是追问与处置复核时的聚合口径。
        assertEquals(task.result().summary(), incidentRegistry.incident(task.incidentId()).orElseThrow().summary());
        assertEquals("RESOLVED", incidentRegistry.incident(task.incidentId()).orElseThrow().status().name());
    }

    @Test
    void doesNotReportMissingUpstreamForHealthyRoute() throws Exception {
        when(routes.routes()).thenReturn(List.of(route("/api", "demo-service", "")));
        when(instances.instances()).thenReturn(List.of(new InstanceSnapshot("demo-service", "", "", "127.0.0.1", 8081, true, 100, true, 0L)));

        TaskView task = await(investigations.submit("/api/hello", "为什么失败？").taskId());

        assertEquals("COMPLETED", task.status().name());
        assertTrue(task.result().summary().contains("1 个匹配的健康实例"));
        assertEquals("LOW", task.result().confidence().name());
    }

    @Test
    void marksMissingRouteDataAndRejectsExternalUrls() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> investigations.submit("http://example.com/api", "诊断"));
        when(routes.routes()).thenThrow(new IllegalStateException("unreachable"));

        TaskView task = await(investigations.submit("/api/demo/tt", "诊断").taskId());

        assertEquals("COMPLETED", task.status().name());
        assertEquals("LOW", task.result().confidence().name());
        assertTrue(task.result().summary().contains("路由数据不足"));
        // 条件边：路由未知时实例数据对该路径没有判定价值，实例采集分支被跳过。
        verify(instances, never()).instances();
    }

    @Test
    void staticUpstreamRouteSkipsInstanceCollection() throws Exception {
        when(routes.routes()).thenReturn(List.of(new RouteSnapshot("legacy", "/static", "", "",
                "http://legacy.internal:8080", 1L)));

        TaskView task = await(investigations.submit("/static/hello", "为什么失败？").taskId());

        assertEquals("COMPLETED", task.status().name());
        assertEquals("LOW", task.result().confidence().name());
        assertTrue(task.result().summary().contains("静态上游"));
        // 静态上游属于路由事实，不作为已确认假设展示。
        assertTrue(task.result().hypotheses().stream().noneMatch(item -> "H2".equals(item.id())));
        verify(instances, never()).instances();
    }

    @Test
    void doesNotUseNameserverInstancesForNacos() throws Exception {
        when(routes.discoveryMode()).thenReturn(DiscoveryMode.NACOS);
        when(routes.routes()).thenReturn(List.of(route("/api", "demo", "")));

        TaskView task = await(investigations.submit("/api/hello", "为什么失败？").taskId());

        assertEquals("LOW", task.result().confidence().name());
        assertTrue(task.result().summary().contains("NACOS"));
        assertTrue(task.result().summary().contains("不能用于判断"));
        verify(instances, never()).instances();
    }

    @Test
    void blankGroupIncludesOtherGroupsAndUnhealthyInstancesAreNotMissing() throws Exception {
        when(routes.routes()).thenReturn(List.of(route("/api", "demo", "")));
        when(instances.instances()).thenReturn(List.of(new InstanceSnapshot("demo", "blue", "", "127.0.0.1", 8081, true, 100, true, 0L)));

        TaskView healthy = await(investigations.submit("/api/hello", "为什么失败？").taskId());
        assertTrue(healthy.result().summary().contains("1 个匹配的健康实例"));

        when(instances.instances()).thenReturn(List.of(new InstanceSnapshot("demo", "blue", "", "127.0.0.1", 8081, false, 100, true, 0L)));
        TaskView unhealthy = await(investigations.submit("/api/hello", "为什么失败？").taskId());
        assertEquals("LOW", unhealthy.result().confidence().name());
        assertTrue(unhealthy.result().summary().contains("可能回退"));
    }

    @Test
    void onlyExactRecent503IsReportedWithoutAttributingCause() throws Exception {
        when(routes.routes()).thenReturn(List.of(route("/api", "demo", "")));
        when(instances.instances()).thenReturn(List.of());
        when(traces.byPath(anyString())).thenReturn(new TraceSnapshot(true, 1.0, List.of(
                new TraceRow("t1", "/api/hello-other", 503, System.currentTimeMillis())), 1L));

        TaskView similar = await(investigations.submit("/api/hello", "为什么失败？").taskId());
        assertEquals("MEDIUM", similar.result().confidence().name());
        assertTrue(similar.result().evidence().stream()
                .anyMatch(item -> item.summary().contains("精确路径匹配追踪 0 条")));

        when(traces.byPath(anyString())).thenReturn(new TraceSnapshot(true, 1.0, List.of(
                new TraceRow("t2", "/api/hello", 503, System.currentTimeMillis())), 1L));
        TaskView exact = await(investigations.submit("/api/hello", "为什么失败？").taskId());
        assertEquals("MEDIUM", exact.result().confidence().name());
        assertTrue(exact.result().summary().contains("无法仅凭状态码确定其原因"));
    }

    @Test
    void investigationRecordsConfirmedAndRejectedHypotheses() throws Exception {
        when(routes.routes()).thenReturn(List.of(route("/api/demo/tt", "demo", "11")));
        when(instances.instances()).thenReturn(List.of(new InstanceSnapshot("demo-service", "", "", "127.0.0.1", 8081, true, 100, true, 0L)));

        TaskView task = await(investigations.submit("/api/demo/tt", "为什么失败？").taskId());

        // 路由已命中 → H1 排除；目标无匹配实例 → H3 确认；结论仍为 MEDIUM。
        assertEquals("REJECTED", hypothesis(task, "H1").status().name());
        assertEquals("CONFIRMED", hypothesis(task, "H3").status().name());
        assertTrue(hypothesis(task, "H3").sources().contains("/api/instances"));
        assertEquals("MEDIUM", task.result().confidence().name());
        assertTrue(task.result().hypotheses().stream().anyMatch(item -> item.id().equals("H5")));
    }

    @Test
    void healthyRouteExcludesMissingInstanceAndUnhealthyHypotheses() throws Exception {
        when(routes.routes()).thenReturn(List.of(route("/api", "demo-service", "")));
        when(instances.instances()).thenReturn(List.of(new InstanceSnapshot("demo-service", "", "", "127.0.0.1", 8081, true, 100, true, 0L)));

        TaskView task = await(investigations.submit("/api/hello", "为什么失败？").taskId());

        // 有健康实例 → 既非「无匹配实例」也非「实例不健康」，两条假设均被排除。
        assertEquals("REJECTED", hypothesis(task, "H3").status().name());
        assertEquals("REJECTED", hypothesis(task, "H4").status().name());
    }

    @Test
    void reportsNotConfiguredWhenNoModelIsPresent() throws Exception {
        when(routes.routes()).thenReturn(List.of(route("/api", "demo", "")));
        when(instances.instances()).thenReturn(List.of());

        TaskView task = await(investigations.submit("/api/hello", "为什么失败？").taskId());

        assertEquals("COMPLETED", task.status().name());
        assertNull(task.result().aiAnalysis());
        // 未配置模型：不得谎报"不可用"，也不能出现 AI 解读步骤。
        assertTrue(task.result().limitations().stream().anyMatch(item -> item.contains("尚未配置模型")));
        assertTrue(task.steps().stream().noneMatch(step -> step.name().equals("AI 解读")));
    }

    @Test
    void reportsUnavailableWhenModelIsConfiguredButNotBuilt() throws Exception {
        when(routes.routes()).thenReturn(List.of(route("/api", "demo", "")));
        when(instances.instances()).thenReturn(List.of());
        useModel(new ModelExplainer(TestGateway.configuredButUnavailable()));

        TaskView task = await(investigations.submit("/api/hello", "为什么失败？").taskId());

        assertEquals("COMPLETED", task.status().name());
        assertNull(task.result().aiAnalysis());
        // 配置了但构建失败：必须被看见，措辞与"尚未配置"区分。
        assertTrue(task.result().limitations().stream().anyMatch(item -> item.contains("模型暂时不可用")));
        assertTrue(task.result().limitations().stream().noneMatch(item -> item.contains("尚未配置模型")));
    }

    @Test
    void explanationIsGroundedInRuntimePrefetchedSnapshotsEvenWhenTheModelCallsNoTool() throws Exception {
        when(routes.routes()).thenReturn(List.of(route("/api", "demo", "")));
        when(instances.instances()).thenReturn(List.of());
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        useModel(new ModelExplainer(TestGateway.of(model)));
        // Spring AI 2.x：工具调用循环在真实 ChatModel 内部执行，mock 无法触发。
        // 模型一个工具都不读也必须能产出解释：路由与实例快照由运行时预读后写进提示词。
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                response("AI 解释（依据运行时预读快照）")));

        TaskView task = await(investigations.submit("/api/hello", "为什么失败？").taskId());

        assertEquals("COMPLETED", task.status().name());
        assertEquals("AI 解释（依据运行时预读快照）", task.result().aiAnalysis());
        assertTrue(task.steps().stream().anyMatch(step -> "AI 解读".equals(step.name())
                && "COMPLETED".equals(step.status().name())
                && step.outputSummary().contains("运行时预读快照：路由快照、实例快照")));
        // 提示词里确实带着预读快照文本：解释有据可依，不靠模型自觉调用工具。
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model).stream(prompts.capture());
        String userText = prompts.getValue().getUserMessage().getText();
        assertTrue(userText.contains("运行时已读取的只读快照："));
        // 快照作为不可信数据被围栏标出，避免其中的文本被当成指令（提示注入隔离）。
        assertTrue(userText.contains("快照 路由快照"));
        assertTrue(userText.contains("ROVER-DATA"));
    }

    /**
     * 解读是流式长调用：用量拿得到就得记下来，否则「模型花了多少钱」无从核算。
     *
     * 同时验证结局口径：一次成功的解读只记一条 {@code outcome=ok}，而不是靠「有没有 error」反推。
     */
    @Test
    void reportsStreamingOutcomeAndTokenUsage() throws Exception {
        when(routes.routes()).thenReturn(List.of(route("/api", "demo", "")));
        when(instances.instances()).thenReturn(List.of());
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        useModel(new ModelExplainer(TestGateway.of(model), new MicrometerAgentMetrics(registry)));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                response("先看路由"),
                responseWithUsage("，再看实例", 1200, 64)));

        TaskView task = await(investigations.submit("/api/hello", "为什么失败？").taskId());

        assertEquals("先看路由，再看实例", task.result().aiAnalysis());
        assertEquals(1.0, registry.get("rover.agent.model.calls")
                .tag("outcome", "ok").tag("scene", "解读").counter().count());
        assertEquals(1200.0, registry.get("rover.agent.model.tokens")
                .tag("kind", "prompt").tag("scene", "解读").counter().count());
        assertEquals(64.0, registry.get("rover.agent.model.tokens")
                .tag("kind", "completion").tag("scene", "解读").counter().count());
    }

    @Test
    void pushesExplanationDeltasToSubscribersAndKeepsTheSameFinalText() throws Exception {
        when(routes.routes()).thenReturn(List.of(route("/api", "demo", "")));
        when(instances.instances()).thenReturn(List.of());
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        useModel(new ModelExplainer(TestGateway.of(model)));
        CountDownLatch subscribed = new CountDownLatch(1);
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            // 等订阅者挂上再出增量：否则连上太晚只会走"补发整段"，测不到增量路径
            subscribed.await();
            readAllTools(invocation.getArgument(0));
            return Flux.just(response("先看路由"), response("，再看实例"));
        });

        TaskView submitted = investigations.submit("/api/hello", "为什么失败？");
        RecordingSubscriber subscriber = new RecordingSubscriber();
        assertTrue(investigations.subscribeEvents(submitted.taskId(), subscriber).isPresent());
        subscribed.countDown();
        TaskView task = await(submitted.taskId());

        // 增量拼接结果与落库文本逐字一致，且订阅者一定收到终态通知。
        assertEquals("先看路由，再看实例", task.result().aiAnalysis());
        subscriber.awaitTerminal();
        assertEquals(List.of("snapshot:", "delta:先看路由", "delta:，再看实例", "end:COMPLETED"),
                subscriber.notes());
        // 步骤说明如实记录证据来源：预读快照与模型另调的工具各自可追溯。
        assertTrue(task.steps().stream().anyMatch(step -> "AI 解读".equals(step.name())
                && step.outputSummary() != null && step.outputSummary().contains("运行时预读快照：路由快照、实例快照")
                && step.outputSummary().contains("模型另调工具：")));
    }

    @Test
    void lateSubscriberOfFinishedTaskGetsSnapshotAndImmediateTerminal() throws Exception {
        when(routes.routes()).thenReturn(List.of(route("/api", "demo", "")));
        when(instances.instances()).thenReturn(List.of());
        TaskView task = await(investigations.submit("/api/hello", "为什么失败？").taskId());

        RecordingSubscriber subscriber = new RecordingSubscriber();
        assertTrue(investigations.subscribeEvents("missing-task", subscriber).isEmpty());
        // 未配置模型：没有增量，但订阅者立刻拿到快照与终态，不能让前端 SSE 连接悬着等超时。
        assertTrue(investigations.subscribeEvents(task.taskId(), subscriber).isPresent());
        subscriber.awaitTerminal();
        assertEquals(List.of("snapshot:", "end:COMPLETED"), subscriber.notes());
    }

    /** 换入不同的模型端口，复用同一组只读端口桩。 */
    private void useModel(ModelExplainer modelExplainer) {
        this.investigations = new InvestigationService(routes, instances, metrics, traces, configs, events,
                incidentRegistry, taskRegistry, modelExplainer);
    }

    @Test
    void rejectedSubmitRollsBackItsSessionAndIncident() throws Exception {
        IncidentRegistry registry = spy(new IncidentRegistry());
        InvestigationTaskRegistry busy = new InvestigationTaskRegistry();
        InvestigationService service = new InvestigationService(routes, instances, metrics, traces, configs, events,
                registry, busy, new ModelExplainer(TestGateway.notConfigured()));
        CountDownLatch release = new CountDownLatch(1);
        when(routes.routes()).thenAnswer(invocation -> {
            release.await();
            return List.of();
        });
        try {
            // 2 个工作线程 + 16 个排队位被阻塞的调查占满，继续提交必然被拒绝。
            for (int i = 0; i < 18; i++) {
                service.submit("/api/hello", "诊断");
            }
            assertThrows(RejectedExecutionException.class, () -> service.submit("/api/hello", "诊断"));

            ArgumentCaptor<String> incidentIds = ArgumentCaptor.forClass(String.class);
            verify(registry).removeIncident(incidentIds.capture());
            ArgumentCaptor<String> sessionIds = ArgumentCaptor.forClass(String.class);
            verify(registry).removeSession(sessionIds.capture());
            // 被拒绝的提交不留孤立会话与事件。
            assertTrue(registry.incident(incidentIds.getValue()).isEmpty());
            assertTrue(registry.session(sessionIds.getValue()).isEmpty());
        } finally {
            release.countDown();
            busy.stop();
        }
    }

    @Test
    void registerCapacityRejectionRollsBackSessionAndIncident() throws Exception {
        IncidentRegistry registry = spy(new IncidentRegistry());
        InvestigationTaskRegistry full = new InvestigationTaskRegistry();
        InvestigationService service = new InvestigationService(routes, instances, metrics, traces, configs, events,
                registry, full, new ModelExplainer(TestGateway.notConfigured()));
        try {
            // 用未结束的任务占满登记容量：这些任务不会被执行，也不会被淘汰，submit 将在登记阶段被拒。
            // 每个占用任务挂在各自的会话下——同一会话同时只允许一个执行中任务（并发约束）。
            int sessionSeq = 0;
            while (true) {
                try {
                    full.register("session-" + (++sessionSeq), "预占容量");
                } catch (RejectedExecutionException ex) {
                    break;
                }
            }
            assertThrows(RejectedExecutionException.class, () -> service.submit("/api/hello", "诊断"));

            ArgumentCaptor<String> incidentIds = ArgumentCaptor.forClass(String.class);
            verify(registry).removeIncident(incidentIds.capture());
            ArgumentCaptor<String> sessionIds = ArgumentCaptor.forClass(String.class);
            verify(registry).removeSession(sessionIds.capture());
            // 登记被拒的提交不留孤立会话与事件。
            assertTrue(registry.incident(incidentIds.getValue()).isEmpty());
            assertTrue(registry.session(sessionIds.getValue()).isEmpty());
        } finally {
            full.stop();
        }
    }

    private static Hypothesis hypothesis(TaskView task, String id) {
        return task.result().hypotheses().stream().filter(item -> id.equals(item.id())).findFirst()
                .orElseThrow(() -> new AssertionError("缺少假设 " + id));
    }

    /** 一段模型增量：流式下每次只承载一小段文本。 */
    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** 带用量元数据的响应：真实服务商多数只在末帧给用量，这里按同样的形态构造。 */
    private static ChatResponse responseWithUsage(String text, int promptTokens, int completionTokens) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .metadata(ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(promptTokens, completionTokens)).build())
                .build();
    }

    /**
     * 替身模型"读一遍"提示词里挂的全部快照工具：用来验证步骤说明里的「模型另调工具」记录，
     * 以及模型额外调用时解读仍然正常。工具回调只有进了提示词才读得到，所以替身的 getOptions()
     * 必须是 ToolCallingChatOptions（真实模型用的 OpenAiChatOptions 本身就实现了它）。
     */
    private static void readAllTools(Prompt prompt) {
        if (prompt.getOptions() instanceof ToolCallingChatOptions options) {
            for (ToolCallback callback : options.getToolCallbacks()) {
                callback.call("{}");
            }
        }
    }

    /**
     * 只记录解读相关事件（快照 / 增量 / 终态），形如 {@code snapshot:}、{@code delta:片段}、{@code end:COMPLETED}。
     * 步骤类事件由任务与事件总线的测试覆盖，这里只关心「解读文本怎么到达订阅者」。
     */
    private static final class RecordingSubscriber implements TaskEventSubscriber {

        private final List<TaskEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void onEvent(TaskEvent event) {
            events.add(event);
        }

        private List<String> notes() {
            List<String> notes = new ArrayList<>();
            boolean snapshotRecorded = false;
            for (TaskEvent event : events) {
                switch (event.type()) {
                    case SNAPSHOT -> {
                        // 意图 / 计划 / 能力进展也会发全量快照（覆盖语义）：这里只记订阅后的第一条，
                        // 后续快照不产生新文本，与前端「快照覆盖、增量追加」的消费方式一致。
                        if (!snapshotRecorded) {
                            notes.add("snapshot:" + ((TaskSnapshot) event.payload()).analysis());
                            snapshotRecorded = true;
                        }
                    }
                    case ANALYSIS_DELTA -> notes.add("delta:" + text(event));
                    default -> {
                        if (event.type().terminal()) {
                            notes.add("end:" + event.type().name().substring("TASK_".length()));
                        }
                    }
                }
            }
            return List.copyOf(notes);
        }

        private void awaitTerminal() {
            await(() -> events.stream().anyMatch(event -> event.type().terminal()));
        }

        private static String text(TaskEvent event) {
            return event.payload() instanceof Map<?, ?> payload && payload.get("text") instanceof String text
                    ? text : "";
        }

        private static void await(BooleanSupplier condition) {
            long deadline = System.currentTimeMillis() + 5000;
            while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            assertTrue(condition.getAsBoolean(), "等待事件超时");
        }
    }

    private static RouteSnapshot route(String prefix, String serviceName, String group) {
        return new RouteSnapshot("id-" + prefix, prefix, serviceName, group, "", 1L);
    }

    private TaskView await(String taskId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        TaskView task;
        do {
            task = investigations.get(taskId);
            if (task.status().name().equals("COMPLETED") || task.status().name().equals("FAILED")) {
                return task;
            }
            Thread.sleep(10);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("诊断任务未完成");
    }

    /** 只覆盖三态语义的模型端口替身，不含任何服务商细节。 */
    private static final class TestGateway implements ChatModelGateway {

        private final boolean configured;
        private final ChatClient client;

        private TestGateway(boolean configured, ChatClient client) {
            this.configured = configured;
            this.client = client;
        }

        static TestGateway notConfigured() {
            return new TestGateway(false, null);
        }

        static TestGateway configuredButUnavailable() {
            return new TestGateway(true, null);
        }

        static TestGateway of(ChatModel model) {
            return new TestGateway(true, ChatClient.builder(model).build());
        }

        @Override
        public boolean configured() {
            return configured;
        }

        @Override
        public boolean available() {
            return configured && client != null;
        }

        @Override
        public ChatClient chatClient() {
            if (client == null) {
                throw new IllegalStateException("模型未就绪");
            }
            return client;
        }

        @Override
        public ChatClient chatClient(int timeoutSeconds) {
            return chatClient();
        }

        @Override
        public String description() {
            return configured ? "测试模型 @ local" : "未配置模型";
        }
    }
}