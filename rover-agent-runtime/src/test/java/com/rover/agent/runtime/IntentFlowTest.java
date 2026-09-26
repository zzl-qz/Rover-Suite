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
import com.rover.agent.core.model.ActionPlan;
import com.rover.agent.core.model.ActionType;
import com.rover.agent.core.model.AgentIntent;
import com.rover.agent.core.model.ResourceTarget;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P2 意图分流验收：同一句自然语言请求会落到不同的执行形态，且每种形态的证据边界都不同。
 *
 * <ul>
 *   <li>状态查询只调一个只读能力，不建事件、不产出调查计划（不是「小一号的故障调查」）；</li>
 *   <li>能力咨询由注册表回答，不进入目标解析，也不需要用户补充请求路径；</li>
 *   <li>处置请求产出不可执行的处置计划，计划本身就是产物；</li>
 *   <li>未开放的请求（定时巡检）如实说明边界，不跑一轮空调查；</li>
 *   <li>故障调查仍按动态计划执行，追问沿用同一事件。</li>
 * </ul>
 */
class IntentFlowTest {

    private static final RouteSnapshot DEMO_ROUTE =
            new RouteSnapshot("r1", "/api/demo/tt", "demo-service", "", "", 1L);
    private static final RouteSnapshot PAY_ROUTE =
            new RouteSnapshot("r2", "/api/pay", "payment-service", "", "", 1L);
    private static final RouteSnapshot ORDER_ROUTE =
            new RouteSnapshot("r3", "/api/order", "order-service", "", "", 1L);

    private InMemoryAgentMessageRepository messages;
    private InMemoryAgentTaskRepository records;
    private InMemoryAgentSessionRepository sessions;
    private InMemoryIncidentRepository incidents;
    private InvestigationTaskRegistry tasks;
    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        sessions = new InMemoryAgentSessionRepository(50);
        incidents = new InMemoryIncidentRepository(50);
        messages = new InMemoryAgentMessageRepository(200);
        records = new InMemoryAgentTaskRepository(50);
        tasks = new InvestigationTaskRegistry(records);
        WorkspaceRetention retention = new WorkspaceRetention(sessions, incidents, messages, tasks, 50, 50, 200);
        IncidentRegistry registry = new IncidentRegistry(sessions, incidents, retention);

        RouteReadPort routes = new RouteReadPort() {
            @Override
            public List<RouteSnapshot> routes() {
                return List.of(DEMO_ROUTE, PAY_ROUTE, ORDER_ROUTE);
            }

            @Override
            public DiscoveryMode discoveryMode() {
                return DiscoveryMode.NAMESERVER;
            }
        };
        InstanceReadPort instances = () -> List.of(
                new InstanceSnapshot("demo-service", "", "10.0.0.7", 8080, true),
                new InstanceSnapshot("order-service", "", "10.0.0.8", 8080, true),
                new InstanceSnapshot("order-service", "", "10.0.0.9", 8080, true),
                new InstanceSnapshot("order-service", "", "10.0.0.10", 8080, false));
        MetricReadPort metrics = windowSeconds -> new GatewayMetricSnapshot(120, 3, 5, System.currentTimeMillis());
        TraceReadPort traces = path -> {
            throw new SnapshotUnavailableException("测试桩未提供追踪");
        };
        ConfigReadPort configs = () -> List.of();
        EventReadPort events = () -> List.of();

        AgentContextManager contexts = new AgentContextManager(sessions, incidents, messages, records, routes,
                instances, 8);
        CapabilityRegistry capabilities = CapabilityRegistry.standard();
        CapabilityExecutor executor = new CapabilityExecutor(routes, instances, metrics, traces, configs, events,
                capabilities);
        InvestigationService investigations = new InvestigationService(routes, instances, metrics, traces, configs,
                events, registry, tasks, new ModelExplainer(new NoopChatModelGateway()));
        orchestrator = new AgentOrchestrator(sessions, incidents, messages, records, registry, contexts,
                new TargetResolver(routes, instances, TargetInterpreter.none()), investigations, retention,
                new IntentService(), new QueryStateService(executor),
                new ExplainService(capabilities, registry, records), new ActionPlanService(executor));
    }

    @AfterEach
    void tearDown() {
        tasks.stop();
    }

    /** 验收 Case 1：全局指标问题只调 GATEWAY_METRICS_QUERY，不进调查链。 */
    @Test
    void metricQueryAnswersFromSingleCapabilityWithoutInvestigation() throws Exception {
        Session session = orchestrator.startSession("admin");

        TaskView task = submitAndAwait(session, "admin", "网关 QPS 多少？");

        assertEquals(TaskType.QUERY, task.taskType());
        assertEquals(AgentIntent.QUERY_STATE, task.intent().intent());
        assertNull(task.incidentId(), "状态查询不是故障调查，不新建事件");
        assertTrue(task.plan().isEmpty(), "状态查询不产出调查计划");
        assertEquals(List.of(AgentCapability.GATEWAY_METRICS_QUERY), task.executedCapabilities());
        assertTrue(task.result().summary().contains("最近 60 秒窗口内请求数 120 次"),
                "回答应来自取回的指标事实，实际为 " + task.result().summary());
        assertEquals(TaskStatus.COMPLETED, task.status());
    }

    /** 验收 Case 2：实例计数问题只调 INSTANCE_QUERY，并落到具体服务上。 */
    @Test
    void instanceQueryCountsHealthyInstancesOfNamedService() throws Exception {
        Session session = orchestrator.startSession("admin");

        TaskView task = submitAndAwait(session, "admin", "order-service 几个健康实例？");

        assertEquals(TaskType.QUERY, task.taskType());
        assertEquals(ResourceTarget.service("order-service"), task.target());
        assertEquals(List.of(AgentCapability.INSTANCE_QUERY), task.executedCapabilities());
        assertTrue(task.result().summary().contains("健康实例 2 个"),
                "回答应给出该服务的健康实例数，实际为 " + task.result().summary());
        assertNull(task.incidentId());
    }

    /** 验收 Case 5：能力咨询由注册表回答，不因解析不出资源而回「请给出请求路径」。 */
    @Test
    void capabilityQuestionAnswersFromRegistryWithoutTargetClarification() throws Exception {
        Session session = orchestrator.startSession("admin");

        TaskView task = submitAndAwait(session, "admin", "你能做什么？");

        assertEquals(TaskType.EXPLAIN, task.taskType());
        assertEquals(AgentIntent.EXPLAIN, task.intent().intent());
        assertEquals(TaskStatus.COMPLETED, task.status());
        assertNull(task.clarification(), "能力咨询不该要求用户补充资源对象");
        String answer = task.result().summary();
        assertTrue(answer.contains("ROUTE_QUERY"), "能力清单必须来自真实注册表，实际为 " + answer);
        // 六个能力全部接入：配置读取与事件查询必须出现在清单里，不再有「尚未开放」段落。
        assertTrue(answer.contains("CONFIG_READ"), "已接入的配置读取能力必须在清单里");
        assertTrue(answer.contains("EVENT_QUERY"), "已接入的事件查询能力必须在清单里");
        assertFalse(answer.contains("尚未开放的能力"), "六个能力全部接入时不应出现未开放段落");
        assertTrue(answer.contains("不执行任何写操作"), "边界声明必须包含「不执行写操作」");
        assertTrue(task.executedCapabilities().isEmpty(), "能力咨询不读生产数据");
    }

    /** 识别不出意图的闲聊（也包含「你是谁」这类问法）：回自我介绍 + 能力清单，不追问资源路径。 */
    @Test
    void unrecognisedMessageIntroducesIdentityAndCapabilities() throws Exception {
        Session session = orchestrator.startSession("admin");

        TaskView task = submitAndAwait(session, "admin", "你好，今天天气怎么样？");

        assertEquals(TaskType.EXPLAIN, task.taskType());
        assertEquals(TaskStatus.COMPLETED, task.status());
        assertNull(task.clarification(), "识别不出意图时应介绍能力，而不是要求用户补充资源对象");
        assertNull(task.incidentId(), "兜底说明不新建事件");
        assertTrue(task.executedCapabilities().isEmpty(), "兜底说明不调用任何能力");
        String answer = task.result().summary();
        assertTrue(answer.contains("我是 Rover Ops Agent"), "先自我介绍，实际为 " + answer);
        assertTrue(answer.contains("ROUTE_QUERY"), "自我介绍要带上真实能力清单");
        assertTrue(answer.contains("不执行任何写操作"), "边界声明必须保留");
    }

    /** 验收 Case 6：处置请求产出可人工审核的不可执行计划，预检只读。 */
    @Test
    void actionRequestProducesNonExecutablePlan() throws Exception {
        Session session = orchestrator.startSession("admin");

        TaskView task = submitAndAwait(session, "admin", "把 order-03 摘掉");

        assertEquals(TaskType.ACTION_PLAN, task.taskType());
        assertEquals(AgentIntent.ACTION_REQUEST, task.intent().intent());
        assertEquals(TaskStatus.COMPLETED, task.status());
        ActionPlan plan = task.actionPlan();
        assertNotNull(plan, "处置请求的产物是计划本身");
        assertEquals(ActionType.DRAIN_INSTANCE, plan.actionType());
        assertFalse(plan.executable(), "本阶段不存在可执行的处置计划");
        assertEquals(ActionPlan.NOT_EXECUTABLE_REASON, plan.blockedReason());
        assertTrue(plan.targetDescription().contains("order-03"),
                "未解析出对象时保留用户原文，实际为 " + plan.targetDescription());
        assertTrue(plan.currentState().length() > 0, "计划需带只读预检看到的当前状态");
        assertEquals(List.of(AgentCapability.INSTANCE_QUERY), task.executedCapabilities());
        assertTrue(task.result().summary().contains("不执行"), "结论必须写明不执行");
    }

    /** 验收 Case 7：未开放的巡检请求如实说明边界，不进入调查、不模拟执行。 */
    @Test
    void scheduledInspectionRequestIsReportedAsUnsupported() throws Exception {
        Session session = orchestrator.startSession("admin");

        TaskView task = submitAndAwait(session, "admin", "每天 9 点自动巡检并发邮件");

        assertEquals(TaskType.UNSUPPORTED, task.taskType());
        assertEquals(AgentIntent.CREATE_INSPECTION, task.intent().intent());
        assertNull(task.incidentId(), "未开放的请求不建事件");
        assertTrue(task.executedCapabilities().isEmpty(), "未开放的请求不调用任何能力");
        assertTrue(task.result().summary().contains("定时巡检尚未开放"),
                "回复必须如实说明边界，实际为 " + task.result().summary());
    }

    /** 验收 Case 3 + 4：故障调查按动态计划执行，追问沿用同一事件与目标。 */
    @Test
    void investigationRunsDynamicPlanAndFollowUpReusesIncident() throws Exception {
        Session session = orchestrator.startSession("admin");

        TaskView first = submitAndAwait(session, "admin", "为什么 /api/demo/tt 调用失败？");
        assertEquals(TaskType.INVESTIGATION, first.taskType());
        assertEquals(AgentIntent.INVESTIGATE, first.intent().intent());
        assertFalse(first.plan().isEmpty(), "调查必须给出可展示的计划");
        assertTrue(first.plan().capabilities().contains(AgentCapability.ROUTE_QUERY));
        assertTrue(first.plan().goal().contains("/api/demo/tt"));
        assertTrue(first.executedCapabilities().contains(AgentCapability.ROUTE_QUERY));

        TaskView second = submitAndAwait(session, "admin", "为什么没有实例？");
        assertEquals(first.incidentId(), second.incidentId(), "追问沿用同一事件");
        assertEquals(ResourceTarget.route("/api/demo/tt"), second.target());
        assertEquals(TaskType.INVESTIGATION, second.taskType());
    }

    private TaskView submitAndAwait(Session session, String userId, String message) throws InterruptedException {
        TaskView submitted =
                orchestrator.submit(session.sessionId(), userId, message, AgentRequestOptions.none()).orElseThrow();
        return await(submitted.taskId());
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
        throw new AssertionError("任务未结束，当前状态 " + task.status());
    }
}