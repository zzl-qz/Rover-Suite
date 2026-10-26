package com.rover.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.capability.CapabilityExecutor;
import com.rover.agent.core.capability.CapabilityRegistry;
import com.rover.agent.core.context.AgentContextManager;
import com.rover.agent.core.context.AgentRequestOptions;
import com.rover.agent.core.context.TargetInterpreter;
import com.rover.agent.core.context.TargetResolver;
import com.rover.agent.core.event.TaskEvent;
import com.rover.agent.core.event.TaskEventSubscription;
import com.rover.agent.core.event.TaskEventType;
import com.rover.agent.core.event.TaskSnapshot;
import com.rover.agent.core.model.AgentMessage;
import com.rover.agent.core.model.AlertEventState;
import com.rover.agent.core.model.AlertIngestResult;
import com.rover.agent.core.model.MessageRole;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.TargetType;
import com.rover.agent.core.model.TaskStatus;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.model.IncidentOrigin;
import com.rover.agent.core.model.TimeRange;
import com.rover.agent.core.port.ConfigReadPort;
import com.rover.agent.core.port.EventReadPort;
import com.rover.agent.core.port.InstanceReadPort;
import com.rover.agent.core.port.KnowledgeReadPort;
import com.rover.agent.core.port.LogQueryPort;
import com.rover.agent.core.port.MetricReadPort;
import com.rover.agent.core.port.RouteReadPort;
import com.rover.agent.core.port.SnapshotUnavailableException;
import com.rover.agent.core.port.TraceReadPort;
import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import com.rover.agent.runtime.journal.OpsJournal;
import com.rover.agent.runtime.repository.InMemoryAgentMessageRepository;
import com.rover.agent.runtime.repository.InMemoryAgentSessionRepository;
import com.rover.agent.runtime.repository.InMemoryAgentTaskRepository;
import com.rover.agent.runtime.repository.InMemoryAlertLifecycleRepository;
import com.rover.agent.runtime.repository.InMemoryIncidentRepository;
import com.rover.agent.runtime.task.IncidentRegistry;
import com.rover.agent.runtime.task.InvestigationTaskRegistry;
import com.rover.agent.runtime.task.SessionTaskRunningException;
import com.rover.agent.runtime.task.WorkspaceRetention;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentOrchestratorTest {

    private static final RouteSnapshot DEMO_ROUTE = new RouteSnapshot("r1", "/api/demo/tt", "demo-service", "",
            "", "", 1L);
    private static final RouteSnapshot PAY_ROUTE = new RouteSnapshot("r2", "/api/pay", "payment-service", "",
            "", "", 1L);

    private InMemoryAgentMessageRepository messages;
    private InMemoryAgentTaskRepository records;
    private InMemoryAgentSessionRepository sessions;
    private InMemoryIncidentRepository incidents;
    private InMemoryAlertLifecycleRepository alertLifecycles;
    private RouteReadPort routePort;
    private InstanceReadPort instancePort;
    private InvestigationTaskRegistry tasks;
    private WorkspaceRetention retention;
    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        sessions = new InMemoryAgentSessionRepository(50);
        incidents = new InMemoryIncidentRepository(50);
        alertLifecycles = new InMemoryAlertLifecycleRepository();
        messages = new InMemoryAgentMessageRepository(200);
        records = new InMemoryAgentTaskRepository(50);
        tasks = new InvestigationTaskRegistry(records);
        retention = new WorkspaceRetention(sessions, incidents, messages, tasks, 50, 50, 200);
        IncidentRegistry registry = new IncidentRegistry(sessions, incidents, retention);

        routePort = new RouteReadPort() {
            @Override
            public List<RouteSnapshot> routes() {
                return List.of(DEMO_ROUTE, PAY_ROUTE);
            }

            @Override
            public DiscoveryMode discoveryMode() {
                return DiscoveryMode.NAMESERVER;
            }
        };
        instancePort = () -> List.of(new InstanceSnapshot("demo-service", "", "", "10.0.0.7", 8080, true, 100, true, 0L));

        orchestrator = buildOrchestrator(routePort);
    }

    @AfterEach
    void tearDown() {
        tasks.stop();
    }

    @Test
    void submitReturnsPendingTaskWithoutWaitingForResolution() throws Exception {
        Session session = orchestrator.startSession("admin");

        long startedAt = System.currentTimeMillis();
        TaskView submitted = submit(session, "admin", "为什么 /api/demo/tt 调用失败？");
        long elapsed = System.currentTimeMillis() - startedAt;

        // 提交即返回：目标解析与调查都在 Worker 里，Servlet 线程不会被管理口 HTTP 或模型调用拖住。
        assertTrue(submitted.status() == TaskStatus.PENDING || submitted.status() == TaskStatus.RUNNING,
                "提交返回时任务应仍在执行或待执行，实际为 " + submitted.status());
        assertFalse(elapsed > 1000, "提交不应等待目标解析，实际耗时 " + elapsed + "ms");

        TaskView finished = await(submitted.taskId());
        assertEquals(TaskStatus.COMPLETED, finished.status());
        assertEquals("/api/demo/tt", finished.path());
    }

    @Test
    void authenticatedAlertCanBeReadObservedAndContinuedOnlyByItsHandler() throws Exception {
        TaskView submitted = orchestrator.ingestAlert("alice", "/api/demo/tt", "5xx 告警", null, null);
        TaskView done = await(submitted.taskId());

        assertTrue(orchestrator.task(done.taskId(), "alice").isPresent());
        assertTrue(orchestrator.task(done.taskId(), "bob").isEmpty());
        assertTrue(orchestrator.workspace(done.sessionId(), "alice", 20).isPresent());
        assertTrue(orchestrator.workspace(done.sessionId(), "bob", 20).isEmpty());
        var incident = incidents.find(done.incidentId()).orElseThrow();
        assertEquals(IncidentOrigin.ALERT, incident.origin());
        assertEquals("alice", incident.triggeredBy());
        assertEquals("alice", sessions.find(done.sessionId()).orElseThrow().userId());
        List<TaskEvent> received = new CopyOnWriteArrayList<>();
        TaskEventSubscription subscription = orchestrator.subscribeEvents(done.taskId(), "alice", received::add)
                .orElseThrow();
        assertTrue(orchestrator.subscribeEvents(done.taskId(), "bob", received::add).isEmpty());
        subscription.cancel();
        TaskView followUp = orchestrator.submit(done.sessionId(), "alice", "继续查这个告警",
                AgentRequestOptions.none()).orElseThrow();
        assertEquals(done.incidentId(), await(followUp.taskId()).incidentId());
        assertEquals("alice", incidents.find(done.incidentId()).orElseThrow().triggeredBy());
    }

    @Test
    void configuredHandlerDoesNotReplaceTheAuthenticatedAlertSource() throws Exception {
        orchestrator = buildOrchestrator(routePort, "alice");
        TaskView submitted = orchestrator.ingestAlert("alerts-service", "/api/demo/tt", "告警", null, null);
        TaskView done = await(submitted.taskId());

        assertEquals("alerts-service", incidents.find(done.incidentId()).orElseThrow().triggeredBy());
        assertEquals("alice", sessions.find(done.sessionId()).orElseThrow().userId());
        assertTrue(orchestrator.task(done.taskId(), "alice").isPresent());
        assertTrue(orchestrator.task(done.taskId(), "alerts-service").isEmpty());
        assertTrue(orchestrator.task(done.taskId(), "bob").isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> orchestrator.ingestAlert("alerts-service", "/api/demo/tt", "告警", 200L, 100L));
        assertThrows(IllegalArgumentException.class,
                () -> orchestrator.ingestAlert("alerts-service", "/api/demo/tt", "告警", 100L, null));
    }

    @Test
    void duplicateFiringDeliveryReturnsOriginalTaskAndResolvedAllowsNextGeneration() throws Exception {
        AlertIngestResult first = orchestrator.ingestAlert("alerts-service", "delivery-1", "cpu-high",
                AlertEventState.FIRING, "/api/demo/tt", "CPU high", null, null);
        assertTrue(first.taskStatus() == TaskStatus.PENDING || first.taskStatus() == TaskStatus.RUNNING);
        AlertIngestResult duplicate = orchestrator.ingestAlert("alerts-service", "delivery-1", "cpu-high",
                AlertEventState.FIRING, "/api/demo/tt", "CPU high", null, null);
        assertTrue(duplicate.duplicate());
        assertEquals(first.taskId(), duplicate.taskId());
        assertEquals(1, sessions.findByUserId("alerts-service").size());

        AlertIngestResult repeat = orchestrator.ingestAlert("alerts-service", "delivery-2", "cpu-high",
                AlertEventState.FIRING, "/api/demo/tt", "CPU still high", null, null);
        assertTrue(repeat.duplicate());
        assertEquals(first.taskId(), repeat.taskId());

        AlertIngestResult resolved = orchestrator.ingestAlert("alerts-service", "delivery-3", "cpu-high",
                AlertEventState.RESOLVED, "/api/demo/tt", "CPU recovered", null, null);
        assertTrue(resolved.resolved());
        assertNull(resolved.taskId());
        assertEquals(com.rover.agent.core.model.IncidentStatus.RESOLVED,
                incidents.find(first.incidentId()).orElseThrow().status());
        TaskView firstTask = orchestrator.task(first.taskId(), "alerts-service").orElseThrow();
        assertEquals(TaskStatus.CANCELLED, firstTask.status());
        AlertIngestResult next = orchestrator.ingestAlert("alerts-service", "delivery-4", "cpu-high",
                AlertEventState.FIRING, "/api/demo/tt", "CPU high again", null, null);
        assertEquals(2, next.generation());
        assertNotNull(next.taskId());
        assertNotNull(firstTask.incidentId());
        await(next.taskId());
        assertEquals(2, sessions.findByUserId("alerts-service").size());
    }

    @Test
    void concurrentDuplicateDeliveryCreatesOnlyOneIncidentAndTask() throws Exception {
        int callers = 8;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<AlertIngestResult>> results = new java.util.ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return orchestrator.ingestAlert("alerts-service", "delivery-concurrent", "cpu-high",
                            AlertEventState.FIRING, "/api/demo/tt", "CPU high", null, null);
                }));
            }
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            List<AlertIngestResult> accepted = results.stream().map(future -> {
                try { return future.get(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError(failure); }
            }).toList();
            assertEquals(1, accepted.stream().map(AlertIngestResult::taskId).distinct().count());
        assertEquals(1L, accepted.stream().filter(result -> !result.duplicate()).count());
            assertEquals(1, sessions.findByUserId("alerts-service").size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void alertDeliveryIdReuseWithDifferentPayloadConflictsAndDoesNotCreateAnotherTask() {
        orchestrator.ingestAlert("alerts-service", "delivery-1", "cpu-high",
                AlertEventState.FIRING, "/api/demo/tt", "CPU high", null, null);
        assertThrows(com.rover.agent.runtime.task.AlertDeliveryConflictException.class, () ->
                orchestrator.ingestAlert("alerts-service", "delivery-1", "cpu-high",
                        AlertEventState.FIRING, "/api/pay", "different payload", null, null));
        assertEquals(1, sessions.findByUserId("alerts-service").size());
    }

    @Test
    void resolvedAlertBeforeQueuedTaskStartsCancelsPendingTask() throws Exception {
        tasks.stop();
        tasks = new InvestigationTaskRegistry(records);
        retention = new WorkspaceRetention(sessions, incidents, messages, tasks, 50, 50, 200);
        orchestrator = buildOrchestrator(routePort);
        AlertIngestResult first = orchestrator.ingestAlert("alerts-service", "delivery-queued-1", "queued-alert",
                AlertEventState.FIRING, "/api/demo/tt", "CPU high", null, null);
        AlertIngestResult resolved = orchestrator.ingestAlert("alerts-service", "delivery-queued-2", "queued-alert",
                AlertEventState.RESOLVED, "/api/demo/tt", "recovered", null, null);
        assertTrue(resolved.resolved());
        assertNull(resolved.taskId());
        assertEquals(TaskStatus.CANCELLED,
                records.find(first.taskId()).orElseThrow().status());
    }

    @Test
    void resolvedUnknownAlertIsRejectedAndDoesNotCreateLifecycle() {
        assertThrows(IllegalArgumentException.class, () -> orchestrator.ingestAlert("alerts-service",
                "delivery-resolved-orphan", "unknown-alert", AlertEventState.RESOLVED,
                "/api/demo/tt", "recovered", null, null));
        assertTrue(sessions.findByUserId("alerts-service").isEmpty());
    }

    @Test
    void followUpQuestionReusesActiveIncidentAndTarget() throws Exception {
        Session session = orchestrator.startSession("admin");

        TaskView first = submitAndAwait(session, "admin", "为什么 /api/demo/tt 调用失败？");
        TaskView second = submitAndAwait(session, "admin", "为什么没有实例？");

        assertNotNull(first.incidentId());
        // 第二轮没有提到任何对象，沿用同一事件与同一取数路径，而不是当成新问题。
        assertEquals(first.incidentId(), second.incidentId());
        assertEquals("/api/demo/tt", second.path());
        assertEquals(ResourceTarget.route("/api/demo/tt"), second.target());
        assertEquals(TaskStatus.COMPLETED, second.status());
        // 每轮只有一条 Agent 回复：结论自己占一条，中间不再有「已开始/已继续调查」的受理播报。
        assertEquals(2, agentReplies(session.sessionId()).size());
    }

    @Test
    void namingAnotherTargetOpensNewIncident() throws Exception {
        Session session = orchestrator.startSession("admin");
        TaskView first = submitAndAwait(session, "admin", "为什么 /api/demo/tt 调用失败？");

        TaskView second = submitAndAwait(session, "admin", "payment-service 为什么这么慢？");

        assertNotNull(second.incidentId());
        assertFalse(first.incidentId().equals(second.incidentId()));
        assertEquals(ResourceTarget.service("payment-service"), second.target());
        assertEquals("/api/pay", second.path());
    }

    /**
     * 对象解析不出不再拦下对话：旧行为是停在 WAITING_INPUT 要用户补「请求路径」，
     * 可「帮我看看最近有没有问题」这种问法本来就答得出来——模型可以自己去查实例与指标。
     * 拿澄清当闸门，正是「稍微模糊一点就整句作废」的来源。
     */
    @Test
    void unresolvableTargetStillReachesTheModelInsteadOfStoppingForInput() throws Exception {
        Session session = orchestrator.startSession("admin");

        TaskView task = submitAndAwait(session, "admin", "帮我看看最近有没有问题");

        assertEquals(TaskStatus.COMPLETED, task.status(), "解析不出对象不该把对话拦在等待输入上");
        assertNull(task.clarification(), "不该再反问用户要请求路径");
        assertNull(task.incidentId(), "没识别出对象就没有事件可挂");
        List<AgentMessage> conversation = orchestrator.conversation(session.sessionId(), "admin");
        assertEquals(2, conversation.size());
        assertEquals(MessageRole.USER, conversation.get(0).role());
        assertEquals(task.taskId(), conversation.get(0).relatedTaskId());
        assertEquals(MessageRole.AGENT, conversation.get(1).role());
        assertEquals(task.taskId(), conversation.get(1).relatedTaskId());
    }

    @Test
    void requestedWindowSurvivesEvenWhenTheTargetNeedsClarification() throws Exception {
        Session session = orchestrator.startSession("admin");
        TimeRange range = new TimeRange(100L, 200L);
        TaskView submitted = orchestrator.submit(session.sessionId(), "admin", "帮我看看最近有没有问题",
                new AgentRequestOptions(ResourceTarget.unknown(), range)).orElseThrow();

        assertEquals(TaskStatus.COMPLETED, await(submitted.taskId()).status());
        assertEquals(range, tasks.find(submitted.taskId()).timeRange());
    }

    @Test
    void secondMessageWhileRunningIsRejectedWithSessionTaskRunning() throws Exception {
        Session session = orchestrator.startSession("admin");
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        RouteReadPort blocked = new RouteReadPort() {
            @Override
            public List<RouteSnapshot> routes() {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return List.of(DEMO_ROUTE, PAY_ROUTE);
            }

            @Override
            public DiscoveryMode discoveryMode() {
                return DiscoveryMode.NAMESERVER;
            }
        };
        rebuildWithRoutes(blocked);
        TaskView running = submit(session, "admin", "为什么 /api/demo/tt 调用失败？");
        started.await();

        SessionTaskRunningException ex = assertThrows(SessionTaskRunningException.class,
                () -> submit(session, "admin", "再问一个 /api/pay 的问题"));

        // 冲突响应带运行中任务 ID，前端据此定位到那张任务卡；且不会再登记第二个任务。
        assertEquals(running.taskId(), ex.runningTaskId());
        assertEquals(1, records.listAll().size());
        release.countDown();
        await(running.taskId());
    }

    @Test
    void conversationIsRecordedInOrderWithBothRoles() throws Exception {
        Session session = orchestrator.startSession("admin");

        TaskView task = submitAndAwait(session, "admin", "为什么 /api/demo/tt 调用失败？");

        List<AgentMessage> conversation = orchestrator.conversation(session.sessionId(), "admin");
        // 两条：提问 + 结论回复。结论自己占一条，气泡里直接是答案，不再有中间的受理播报。
        assertEquals(2, conversation.size());
        assertEquals(MessageRole.USER, conversation.get(0).role());
        assertEquals(MessageRole.AGENT, conversation.get(1).role());
        assertEquals(task.result().summary(), conversation.get(1).content());
        assertEquals(task.taskId(), conversation.get(0).relatedTaskId());
        assertEquals(task.taskId(), conversation.get(1).relatedTaskId());
        // 首次提问生成会话标题，供会话列表展示。
        assertEquals("为什么 /api/demo/tt 调用失败？", orchestrator.session(session.sessionId(), "admin")
                .orElseThrow().title());
    }

    @Test
    void sessionsAreVisibleOnlyToTheirOwner() {
        Session session = orchestrator.startSession("admin");

        assertTrue(orchestrator.session(session.sessionId(), "other").isEmpty());
        assertTrue(orchestrator.sessions("other").isEmpty());
        assertTrue(orchestrator.submit(session.sessionId(), "other", "为什么 /api/demo/tt 失败？",
                AgentRequestOptions.none()).isEmpty());
        assertEquals(1, orchestrator.sessions("admin").size());
    }

    @Test
    void taskLookupRespectsSessionOwnership() throws Exception {
        Session session = orchestrator.startSession("admin");
        TaskView task = submitAndAwait(session, "admin", "为什么 /api/demo/tt 调用失败？");

        assertTrue(orchestrator.task(task.taskId(), "admin").isPresent());
        assertTrue(orchestrator.task(task.taskId(), "other").isEmpty());
    }

    /** 聚合视图一次取齐会话、对话、事件与最近任务，任务按创建时间倒序且受 limit 约束。 */
    @Test
    void workspaceAggregatesSessionAndIsScopedToOwner() throws Exception {
        Session session = orchestrator.startSession("admin");
        TaskView first = submitAndAwait(session, "admin", "为什么 /api/demo/tt 调用失败？");
        TaskView second = submitAndAwait(session, "admin", "payment-service 为什么这么慢？");

        AgentOrchestrator.Workspace workspace = orchestrator.workspace(session.sessionId(), "admin", 1)
                .orElseThrow();

        assertEquals(session.sessionId(), workspace.session().sessionId());
        // 两次提问各两条：提问 + 结论回复。
        assertEquals(4, workspace.messages().size());
        assertEquals(2, workspace.incidents().size());
        // limit 之外的旧任务不在聚合里，最新的排在最前。
        assertEquals(List.of(second.taskId()), workspace.tasks().stream().map(TaskView::taskId).toList());
        // 当前事件随最后一次提问切换：追问的第二轮问的是 payment-service。
        assertEquals(second.incidentId(), workspace.activeIncident().incidentId());
        assertTrue(orchestrator.workspace(session.sessionId(), "other", 20).isEmpty(),
                "别人的会话不该被聚合出来");
        assertFalse(first.taskId().equals(second.taskId()));
    }

    /** 事件订阅与任务详情共用同一套归属判定：换个用户或换个任务 ID 都拿不到订阅。 */
    @Test
    void eventSubscriptionRespectsTaskOwnership() throws Exception {
        Session session = orchestrator.startSession("admin");
        TaskView task = submitAndAwait(session, "admin", "为什么 /api/demo/tt 调用失败？");

        List<TaskEvent> received = new CopyOnWriteArrayList<>();
        assertTrue(orchestrator.subscribeEvents(task.taskId(), "other", received::add).isEmpty());
        assertTrue(orchestrator.subscribeEvents("missing", "admin", received::add).isEmpty());
        assertTrue(received.isEmpty(), "未通过归属校验的订阅不该收到任何事件");

        TaskEventSubscription subscription =
                orchestrator.subscribeEvents(task.taskId(), "admin", received::add).orElseThrow();
        // 任务已结束：晚订阅先拿到快照，再拿到终态事件，这条流自然收尾而不是悬着等超时。
        awaitEvents(received, 2);
        subscription.cancel();

        assertEquals(TaskEventType.SNAPSHOT, received.get(0).type());
        assertEquals(TaskStatus.COMPLETED, ((TaskSnapshot) received.get(0).payload()).task().status());
        assertEquals(TaskEventType.TASK_COMPLETED, received.get(1).type());
        assertEquals(task.taskId(), received.get(1).taskId());
    }

    @Test
    void explicitAdvancedTargetIsUsedWhenQuestionNamesNothing() throws Exception {
        Session session = orchestrator.startSession("admin");

        TaskView submitted = orchestrator.submit(session.sessionId(), "admin", "它到底是哪里出了问题？",
                new AgentRequestOptions(ResourceTarget.service("demo-service"),
                        com.rover.agent.core.model.TimeRange.unspecified())).orElseThrow();
        TaskView task = await(submitted.taskId());

        assertEquals(TargetType.SERVICE, task.target().type());
        assertEquals("/api/demo/tt", task.path());
    }

    /** 用阻塞的只读端口重建编排链：用于制造「任务正在执行」的并发现场。 */
    private void rebuildWithRoutes(RouteReadPort routes) {
        orchestrator = buildOrchestrator(routes);
    }

    /** 按给定路由端口装配完整编排链（会话 → 目标 → 事件 → 对话主路径）。 */
    private AgentOrchestrator buildOrchestrator(RouteReadPort routes) {
        return buildOrchestrator(routes, null);
    }

    private AgentOrchestrator buildOrchestrator(RouteReadPort routes, String alertOwner) {
        MetricReadPort metricPort = windowSeconds -> {
            throw new SnapshotUnavailableException("测试桩未提供指标");
        };
        TraceReadPort tracePort = path -> {
            throw new SnapshotUnavailableException("测试桩未提供追踪");
        };
        IncidentRegistry registry = new IncidentRegistry(sessions, incidents, retention);
        ConfigReadPort configPort = () -> List.of();
        EventReadPort eventPort = () -> List.of();
        LogQueryPort logPort = r -> List.of();
        KnowledgeReadPort knowledgePort = (q, limit) -> List.of();
        AgentContextManager contexts = new AgentContextManager(sessions, incidents, messages, records, routes,
                instancePort, 8);
        CapabilityExecutor executor = new CapabilityExecutor(routes, instancePort, metricPort, tracePort,
                configPort, eventPort, logPort, knowledgePort, CapabilityRegistry.standard());
        var toolLoop = ScriptedConversationModel.service(executor, tools -> {
            tools.listInstances("demo-service");
            return "已按问题作答。";
        });
        InvestigationService investigations = new InvestigationService(registry, tasks, toolLoop,
                OpsJournal.none(), alertOwner, alertLifecycles);
        return new AgentOrchestrator(sessions, incidents, messages, records, registry, contexts,
                new TargetResolver(routes, instancePort, TargetInterpreter.none()), investigations, retention,
                toolLoop, OpsJournal.none());
    }

    private TaskView submit(Session session, String userId, String message) {
        return orchestrator.submit(session.sessionId(), userId, message, AgentRequestOptions.none()).orElseThrow();
    }

    private TaskView submitAndAwait(Session session, String userId, String message) throws InterruptedException {
        return await(submit(session, userId, message).taskId());
    }

    /** 会话里的 Agent 回复，按发生顺序。 */
    private List<AgentMessage> agentReplies(String sessionId) {
        return messages.bySession(sessionId).stream()
                .filter(message -> message.role() == MessageRole.AGENT)
                .toList();
    }

    /** 等待任务离开执行态（PENDING / RUNNING），返回最终快照。 */
    private TaskView await(String taskId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        TaskView task;
        do {
            Optional<TaskView> found = records.find(taskId);
            task = found.orElseThrow(() -> new AssertionError("任务已被淘汰：" + taskId));
            if (!task.status().active()) {
                return task;
            }
            Thread.sleep(10);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("调查任务未结束，当前状态 " + task.status());
    }

    /** 等待订阅者收到至少 {@code count} 条事件；事件在独立派发线程上投递，所以这里只能等。 */
    private static void awaitEvents(List<TaskEvent> received, int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (received.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(count, received.size(), "未在超时前收到事件：" + received);
    }
}
