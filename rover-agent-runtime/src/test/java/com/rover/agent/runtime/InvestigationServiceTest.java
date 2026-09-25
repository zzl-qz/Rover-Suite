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

import com.rover.agent.core.model.Hypothesis;
import com.rover.agent.core.model.TaskView;
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
import com.rover.agent.runtime.llm.ModelExplainer;
import com.rover.agent.runtime.task.IncidentRegistry;
import com.rover.agent.runtime.task.InvestigationTaskRegistry;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

/** 调查用例的场景级验证：只读端口输入 → 步骤、证据、假设结论与降级行为。 */
class InvestigationServiceTest {

    private RouteReadPort routes;
    private InstanceReadPort instances;
    private MetricReadPort metrics;
    private TraceReadPort traces;
    private IncidentRegistry incidentRegistry;
    private InvestigationTaskRegistry taskRegistry;
    private ObjectProvider<ChatModel> models;
    private ObjectProvider<ChatClient.Builder> builders;
    private InvestigationService investigations;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        routes = mock(RouteReadPort.class);
        instances = mock(InstanceReadPort.class);
        metrics = mock(MetricReadPort.class);
        traces = mock(TraceReadPort.class);
        models = mock(ObjectProvider.class);
        builders = mock(ObjectProvider.class);
        incidentRegistry = new IncidentRegistry();
        taskRegistry = new InvestigationTaskRegistry();
        investigations = new InvestigationService(routes, instances, metrics, traces, incidentRegistry, taskRegistry,
                new ModelExplainer(models, builders));
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
        when(instances.instances()).thenReturn(List.of(new InstanceSnapshot("demo-service", "",
                "127.0.0.1", 8081, true)));

        TaskView task = await(investigations.submit("/api/demo/tt", "为什么失败？").taskId());

        assertEquals("COMPLETED", task.status().name());
        assertEquals("MEDIUM", task.result().confidence().name());
        assertTrue(task.result().summary().contains("demo / 11"));
        assertTrue(task.result().evidence().stream().anyMatch(item -> item.source().equals("/api/routes")));
        assertTrue(task.result().evidence().stream().anyMatch(item -> item.source().equals("/api/instances")));
        // 提问会开启会话与事件，任务挂在事件下：连续追问与处置复核才有聚合点。
        assertNotNull(task.sessionId());
        assertNotNull(task.incidentId());
        assertTrue(incidentRegistry.incident(task.incidentId()).orElseThrow().taskIds().contains(task.taskId()));
        assertEquals(task.sessionId(), incidentRegistry.incident(task.incidentId()).orElseThrow().sessionId());
        assertTrue(incidentRegistry.session(task.sessionId()).isPresent());
    }

    @Test
    void doesNotReportMissingUpstreamForHealthyRoute() throws Exception {
        when(routes.routes()).thenReturn(List.of(route("/api", "demo-service", "")));
        when(instances.instances()).thenReturn(List.of(new InstanceSnapshot("demo-service", "",
                "127.0.0.1", 8081, true)));

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
        when(instances.instances()).thenReturn(List.of(new InstanceSnapshot("demo", "blue",
                "127.0.0.1", 8081, true)));

        TaskView healthy = await(investigations.submit("/api/hello", "为什么失败？").taskId());
        assertTrue(healthy.result().summary().contains("1 个匹配的健康实例"));

        when(instances.instances()).thenReturn(List.of(new InstanceSnapshot("demo", "blue",
                "127.0.0.1", 8081, false)));
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
                .anyMatch(item -> item.detail().contains("精确路径匹配追踪 0 条")));

        when(traces.byPath(anyString())).thenReturn(new TraceSnapshot(true, 1.0, List.of(
                new TraceRow("t2", "/api/hello", 503, System.currentTimeMillis())), 1L));
        TaskView exact = await(investigations.submit("/api/hello", "为什么失败？").taskId());
        assertEquals("MEDIUM", exact.result().confidence().name());
        assertTrue(exact.result().summary().contains("无法仅凭状态码确定其原因"));
    }

    @Test
    void investigationRecordsConfirmedAndRejectedHypotheses() throws Exception {
        when(routes.routes()).thenReturn(List.of(route("/api/demo/tt", "demo", "11")));
        when(instances.instances()).thenReturn(List.of(new InstanceSnapshot("demo-service", "",
                "127.0.0.1", 8081, true)));

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
        when(instances.instances()).thenReturn(List.of(new InstanceSnapshot("demo-service", "",
                "127.0.0.1", 8081, true)));

        TaskView task = await(investigations.submit("/api/hello", "为什么失败？").taskId());

        // 有健康实例 → 既非「无匹配实例」也非「实例不健康」，两条假设均被排除。
        assertEquals("REJECTED", hypothesis(task, "H3").status().name());
        assertEquals("REJECTED", hypothesis(task, "H4").status().name());
    }

    @Test
    void modelMustReadSnapshotToolsBeforeServingExplanation() throws Exception {
        when(routes.routes()).thenReturn(List.of(route("/api", "demo", "")));
        when(instances.instances()).thenReturn(List.of());
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        when(models.getIfAvailable()).thenReturn(model);
        when(builders.getObject()).thenReturn(ChatClient.builder(model));
        // Spring AI 2.x：工具调用循环在真实 ChatModel 内部执行，mock 无法触发；
        // 模型直接返回未读取任何快照的答案，正好验证 guard：不得采信未读取证据的解释。
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("AI 解释（未读取证据）")))));

        TaskView task = await(investigations.submit("/api/hello", "为什么失败？").taskId());

        assertEquals("COMPLETED", task.status().name());
        // 模型未读取路由/实例快照，guard 拒绝其解释，回退为规则诊断。
        assertNull(task.result().aiAnalysis());
        assertTrue(task.result().limitations().stream().anyMatch(item -> item.contains("模型暂时不可用")));
        assertTrue(task.steps().stream().anyMatch(step -> step.name().equals("AI 解读")
                && step.status().name().equals("FAILED")));
    }

    @Test
    void rejectedSubmitRollsBackItsSessionAndIncident() throws Exception {
        IncidentRegistry registry = spy(new IncidentRegistry());
        InvestigationTaskRegistry busy = new InvestigationTaskRegistry();
        InvestigationService service = new InvestigationService(routes, instances, metrics, traces, registry, busy,
                new ModelExplainer(models, builders));
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
        InvestigationService service = new InvestigationService(routes, instances, metrics, traces, registry, full,
                new ModelExplainer(models, builders));
        try {
            // 用未结束的任务占满登记容量：这些任务不会被执行，也不会被淘汰，submit 将在登记阶段被拒。
            while (true) {
                try {
                    full.register("session-x", "incident-x", "/api/hello", "预占容量");
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
}