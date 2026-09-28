package com.rover.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.capability.CapabilityExecutor;
import com.rover.agent.core.capability.CapabilityRegistry;
import com.rover.agent.core.context.AgentContextManager;
import com.rover.agent.core.context.AgentRequestOptions;
import com.rover.agent.core.context.TargetInterpreter;
import com.rover.agent.core.context.TargetResolver;
import com.rover.agent.core.intent.IntentService;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.TaskStatus;
import com.rover.agent.core.model.TaskType;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.port.ConfigReadPort;
import com.rover.agent.core.port.EventReadPort;
import com.rover.agent.core.port.InstanceReadPort;
import com.rover.agent.core.port.MetricReadPort;
import com.rover.agent.core.port.RouteReadPort;
import com.rover.agent.core.port.SnapshotUnavailableException;
import com.rover.agent.core.port.TraceReadPort;
import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.core.snapshot.GatewayMetricSnapshot;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import com.rover.agent.runtime.llm.ModelExplainer;
import com.rover.agent.runtime.llm.NoopChatModelGateway;
import com.rover.agent.runtime.repository.InMemoryAgentMessageRepository;
import com.rover.agent.runtime.repository.InMemoryAgentSessionRepository;
import com.rover.agent.runtime.repository.InMemoryAgentTaskRepository;
import com.rover.agent.runtime.repository.InMemoryIncidentRepository;
import com.rover.agent.runtime.task.IncidentRegistry;
import com.rover.agent.runtime.task.InvestigationTaskRegistry;
import com.rover.agent.runtime.task.WorkspaceRetention;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 对话主路径的行为契约。
 *
 * 这些用例守的是<b>编排职责</b>：工具是否落到真实取数、证据有没有随结论落库、
 * 一次对话能否取多个来源、预算耗尽如何收尾、失败怎样收敛成可读结论、模型不可用时是否诚实。
 * 模型侧由 {@link ScriptedConversationModel} 替换，因此每条断言都只关于编排，不掺模型行为。
 *
 * 「模型会不会自己拆子问题、会不会选对工具」是模型能力，这里不假装保证——
 * 它由真实模型的端到端场景验证。
 */
class ConversationFlowTest {

    private static final RouteSnapshot DEMO_ROUTE =
            new RouteSnapshot("r1", "/api/demo/tt", "demo-service", "", "", "", 1L);

    private InMemoryAgentMessageRepository messages;
    private InMemoryAgentTaskRepository records;
    private InMemoryAgentSessionRepository sessions;
    private InMemoryIncidentRepository incidents;
    private InvestigationTaskRegistry tasks;
    private RouteReadPort routes;
    private InstanceReadPort instances;
    private MetricReadPort metrics;
    private TraceReadPort traces;
    private ConfigReadPort configs;
    private EventReadPort events;

    @AfterEach
    void tearDown() {
        if (tasks != null) {
            tasks.stop();
        }
    }

    /** 闲聊不该去查生产数据。 */
    @Test
    void chitchatIsAnsweredWithoutTouchingAnyData() throws Exception {
        AgentOrchestrator agent = build(tools ->
                "你好，我是 Rover Ops Agent，负责读网关与注册中心的真实数据回答运维问题。");
        Session session = agent.startSession("admin");

        TaskView task = submitAndAwait(agent, session, "你好");

        assertEquals(TaskType.CONVERSATION, task.taskType());
        assertEquals(TaskStatus.COMPLETED, task.status());
        assertTrue(task.executedCapabilities().isEmpty(), "闲聊不该调用任何只读能力");
        assertTrue(task.result().evidence().isEmpty(), "没有取数就没有证据");
        assertTrue(task.result().summary().contains("Rover Ops Agent"));
    }

    /** 工具是真取数：调用要落到只读能力上，取回的事实要随结论落库，结论才可复核。 */
    @Test
    void toolCallReachesRealDataAndLandsAsEvidence() throws Exception {
        AgentOrchestrator agent = build(tools -> {
            String facts = tools.listInstances("demo-service");
            return "demo-service 的实例情况如下：\n" + facts;
        });
        Session session = agent.startSession("admin");

        TaskView task = submitAndAwait(agent, session, "demo-service 现在什么情况");

        assertEquals(List.of(AgentCapability.INSTANCE_QUERY), task.executedCapabilities());
        assertFalse(task.result().evidence().isEmpty(), "取到的事实必须随结论落库，否则结论无法复核");
        assertTrue(task.result().summary().contains("实例"), "实际为 " + task.result().summary());
    }

    /** 一句话问多件事：模型为一个问题取几次数，几次都要被记录下来。 */
    @Test
    void oneTurnMayQuerySeveralSources() throws Exception {
        AgentOrchestrator agent = build(tools -> {
            String instanceFacts = tools.listInstances("");
            String metricFacts = tools.getGatewayMetrics("");
            return "实例：" + instanceFacts + "\n流量：" + metricFacts;
        });
        Session session = agent.startSession("admin");

        TaskView task = submitAndAwait(agent, session, "现在有哪些实例？网关流量多少？");

        assertTrue(task.executedCapabilities().contains(AgentCapability.INSTANCE_QUERY),
                "实例那一问要真的取数，实际为 " + task.executedCapabilities());
        assertTrue(task.executedCapabilities().contains(AgentCapability.GATEWAY_METRICS_QUERY),
                "流量那一问也要真的取数，实际为 " + task.executedCapabilities());
    }

    /** 预算耗尽：不是失败，也不是静默丢弃，而是明确让模型基于已有事实收尾。 */
    @Test
    void budgetExhaustionAsksTheModelToWrapUpInsteadOfFailing() throws Exception {
        AgentOrchestrator agent = build(tools -> {
            String last = "";
            for (int index = 0; index < 40; index++) {
                last = tools.listInstances("");
            }
            return last;
        });
        Session session = agent.startSession("admin");

        TaskView task = submitAndAwait(agent, session, "把所有实例挨个查一遍");

        assertEquals(TaskStatus.COMPLETED, task.status(), "触顶是收尾信号，不该把任务判成失败");
        assertTrue(task.result().summary().contains("查询次数已达上限"),
                "触顶后要让模型知道该收尾了，实际为 " + task.result().summary());
    }

    /** 模型失败：结论要能读，并说清已经取到了什么，而不是笼统一句失败。 */
    @Test
    void modelFailureLeavesReadableConclusion() throws Exception {
        AgentOrchestrator agent = build(tools -> {
            tools.listInstances("demo-service");
            throw new IllegalStateException("上游连接被重置");
        });
        Session session = agent.startSession("admin");

        TaskView task = submitAndAwait(agent, session, "demo-service 怎么了");

        assertEquals(TaskStatus.FAILED, task.status());
        assertTrue(task.error().contains("已取数 1 次"),
                "失败要说清已经取到什么（这些数据仍然可查），实际为 " + task.error());
        assertTrue(task.error().contains("上游连接被重置"), "原始失败原因要保留");
    }

    /**
     * 模型不可用：如实说明，不回退到另一套规则流程冒充同一个 Agent。
     *
     * 这条是刻意的取舍：主路径的每一次取数与每一句结论都出自模型，没有模型时诚实的回复是
     * 「现在不能用」，而不是给一个看起来还能跑的假象。
     */
    @Test
    void modelUnavailableIsReportedHonestly() throws Exception {
        preparePorts();
        CapabilityExecutor executor = newExecutor();
        AgentOrchestrator agent = assemble(executor, new ToolLoopService(executor, new NoopChatModelGateway(),
                ScriptedConversationModel.answering(tools -> "不该被调用")));
        Session session = agent.startSession("admin");

        TaskView task = submitAndAwait(agent, session, "网关现在怎么样");

        assertEquals(TaskStatus.COMPLETED, task.status());
        assertTrue(task.result().summary().contains("没有配置可用的模型"),
                "未配置模型时要说清原因，实际为 " + task.result().summary());
        assertTrue(task.executedCapabilities().isEmpty(), "没有模型时不取任何数");
    }

    /** 思考是过程、答案是结论：思考内容不会混进结论正文。 */
    @Test
    void thinkingDoesNotLeakIntoTheAnswer() throws Exception {
        preparePorts();
        CapabilityExecutor executor = newExecutor();
        AgentOrchestrator agent = assemble(executor, ScriptedConversationModel.thinkingService(executor,
                "先看一下 demo-service 的实例，再决定要不要查指标。",
                tools -> {
                    tools.listInstances("demo-service");
                    return "demo-service 有一个健康实例。";
                }));
        Session session = agent.startSession("admin");

        TaskView task = submitAndAwait(agent, session, "demo-service 有几个健康实例");

        assertEquals("demo-service 有一个健康实例。", task.result().summary());
        assertFalse(task.result().summary().contains("先看一下"),
                "思考内容只走思考流，不该出现在结论里");
    }

    /**
     * 列全部路由：工具集必须提供这个能力。
     *
     * 「网关一共有几条路由」是常问的问题，而只提供按路径查时，模型只能猜前缀逐个探测——
     * 猜不到的前缀会被静默漏掉，它却会给出一个看起来确定的条数。
     */
    @Test
    void listRoutesReturnsEveryRouteInOneCall() throws Exception {
        AgentOrchestrator agent = build(tools -> tools.listRoutes());
        Session session = agent.startSession("admin");

        TaskView task = submitAndAwait(agent, session, "网关一共有几条路由？");

        assertEquals(List.of(AgentCapability.ROUTE_QUERY), task.executedCapabilities());
        assertTrue(task.result().summary().contains("共 1 条路由"),
                "一次调用就要拿全，实际为 " + task.result().summary());
    }

    /** 识别出对象就按对象聚合成事件：同一对象的多次对话因此连得上。 */
    @Test
    void conversationAggregatesByTarget() throws Exception {
        AgentOrchestrator agent = build(tools -> "已查看这条路由。");
        Session session = agent.startSession("admin");

        TaskView task = submitAndAwait(agent, session, "/api/demo/tt 这条路由现在怎么样");

        assertEquals(TaskType.CONVERSATION, task.taskType(), "主路径不再按问题预归类");
        assertNotNull(task.incidentId(), "识别出对象时要聚合成事件，追问才连得上");
    }

    /** 没识别出对象时不建事件，也不该拦下对话。 */
    @Test
    void conversationWithoutTargetStillCompletesWithoutIncident() throws Exception {
        AgentOrchestrator agent = build(tools -> "我看了注册中心的整体情况。");
        Session session = agent.startSession("admin");

        TaskView task = submitAndAwait(agent, session, "帮我看看最近有没有问题");

        assertEquals(TaskStatus.COMPLETED, task.status(), "解析不出对象不该把对话拦在等待输入上");
        assertNull(task.clarification(), "不该反问用户要请求路径");
        assertNull(task.incidentId(), "没有对象就没有事件可挂");
    }

    // ------------------------------------------------------------------ 装配

    /** 按脚本装配一条完整编排链：脚本决定「模型这一步做什么」。 */
    private AgentOrchestrator build(ScriptedConversationModel.Script script) {
        preparePorts();
        CapabilityExecutor executor = newExecutor();
        return assemble(executor, new ToolLoopService(executor, ScriptedConversationModel.gateway(),
                ScriptedConversationModel.answering(script)));
    }

    /**
     * 装配完整编排链。
     *
     * 每条用例各装配一次、不复用共享实例：脚本不同则链路不同，
     * 共享一个 orchestrator 会让「这条用例验的是哪套桩」变得难以看出。
     */
    private AgentOrchestrator assemble(CapabilityExecutor executor, ToolLoopService toolLoop) {
        sessions = new InMemoryAgentSessionRepository(50);
        incidents = new InMemoryIncidentRepository(50);
        messages = new InMemoryAgentMessageRepository(200);
        records = new InMemoryAgentTaskRepository(50);
        tasks = new InvestigationTaskRegistry(records);
        WorkspaceRetention retention = new WorkspaceRetention(sessions, incidents, messages, tasks, 50, 50, 200);
        IncidentRegistry registry = new IncidentRegistry(sessions, incidents, retention);
        AgentContextManager contexts = new AgentContextManager(sessions, incidents, messages, records, routes,
                instances, 8);
        CapabilityRegistry capabilities = CapabilityRegistry.standard();
        InvestigationService investigations = new InvestigationService(routes, instances, metrics, traces, configs,
                events, r -> List.of(), (q, limit) -> List.of(), registry, tasks,
                new ModelExplainer(new NoopChatModelGateway()));
        return new AgentOrchestrator(sessions, incidents, messages, records, registry, contexts,
                new TargetResolver(routes, instances, TargetInterpreter.none()), investigations, retention,
                new IntentService(), new QueryStateService(executor),
                new ExplainService(capabilities, registry, records), new ActionPlanService(executor), toolLoop,
                com.rover.agent.runtime.journal.OpsJournal.none());
    }

    /** 只读端口桩：两个实例，一台健康一台不健康，够验证「取到的事实长什么样」。 */
    private void preparePorts() {
        routes = new RouteReadPort() {
            @Override
            public List<RouteSnapshot> routes() {
                return List.of(DEMO_ROUTE);
            }

            @Override
            public DiscoveryMode discoveryMode() {
                return DiscoveryMode.NAMESERVER;
            }
        };
        instances = () -> List.of(
                new InstanceSnapshot("demo-service", "", "", "10.0.0.7", 8080, true, 100, true, 0L),
                new InstanceSnapshot("demo-service", "", "", "10.0.0.8", 8080, false, 100, true, 0L));
        metrics = windowSeconds -> new GatewayMetricSnapshot(120, 3, 5, System.currentTimeMillis());
        traces = path -> {
            throw new SnapshotUnavailableException("测试桩未提供追踪");
        };
        configs = () -> List.of();
        events = () -> List.of();
    }

    private CapabilityExecutor newExecutor() {
        return new CapabilityExecutor(routes, instances, metrics, traces, configs, events, r -> List.of(),
                (q, limit) -> List.of(), CapabilityRegistry.standard());
    }

    private static TaskView submitAndAwait(AgentOrchestrator agent, Session session, String message)
            throws InterruptedException {
        TaskView submitted = agent.submit(session.sessionId(), "admin", message, AgentRequestOptions.none())
                .orElseThrow();
        return await(agent, submitted.taskId());
    }

    /** 等待任务离开执行态（PENDING / RUNNING），返回最终快照。 */
    private static TaskView await(AgentOrchestrator agent, String taskId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        TaskView task;
        do {
            Optional<TaskView> found = agent.task(taskId, "admin");
            task = found.orElseThrow(() -> new AssertionError("任务已被淘汰：" + taskId));
            if (!task.status().active()) {
                return task;
            }
            Thread.sleep(10);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("任务未结束，当前状态 " + task.status());
    }
}
