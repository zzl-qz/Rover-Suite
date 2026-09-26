package com.rover.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.context.AgentContextManager;
import com.rover.agent.core.context.AgentRequestOptions;
import com.rover.agent.core.context.TargetInterpreter;
import com.rover.agent.core.context.TargetResolver;
import com.rover.agent.core.model.AgentMessage;
import com.rover.agent.core.model.AgentResponse;
import com.rover.agent.core.model.MessageRole;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.TargetType;
import com.rover.agent.core.port.InstanceReadPort;
import com.rover.agent.core.port.MetricReadPort;
import com.rover.agent.core.port.RouteReadPort;
import com.rover.agent.core.port.SnapshotUnavailableException;
import com.rover.agent.core.port.TraceReadPort;
import com.rover.agent.core.snapshot.DiscoveryMode;
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
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentOrchestratorTest {

    private static final RouteSnapshot DEMO_ROUTE = new RouteSnapshot("r1", "/api/demo/tt", "demo-service", "",
            "", 1L);
    private static final RouteSnapshot PAY_ROUTE = new RouteSnapshot("r2", "/api/pay", "payment-service", "",
            "", 1L);

    private InvestigationTaskRegistry tasks;
    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        InMemoryAgentSessionRepository sessions = new InMemoryAgentSessionRepository(50);
        InMemoryIncidentRepository incidents = new InMemoryIncidentRepository(50);
        InMemoryAgentMessageRepository messages = new InMemoryAgentMessageRepository(200);
        InMemoryAgentTaskRepository records = new InMemoryAgentTaskRepository(50);
        IncidentRegistry registry = new IncidentRegistry(sessions, incidents);
        tasks = new InvestigationTaskRegistry(records);

        RouteReadPort routePort = new RouteReadPort() {
            @Override
            public List<RouteSnapshot> routes() {
                return List.of(DEMO_ROUTE, PAY_ROUTE);
            }

            @Override
            public DiscoveryMode discoveryMode() {
                return DiscoveryMode.NAMESERVER;
            }
        };
        InstanceReadPort instancePort = () -> List.of(new InstanceSnapshot("demo-service", "", "10.0.0.7", 8080,
                true));
        MetricReadPort metricPort = windowSeconds -> {
            throw new SnapshotUnavailableException("测试桩未提供指标");
        };
        TraceReadPort tracePort = path -> {
            throw new SnapshotUnavailableException("测试桩未提供追踪");
        };

        InvestigationService investigations = new InvestigationService(routePort, instancePort, metricPort,
                tracePort, registry, tasks, new ModelExplainer(new NoopChatModelGateway()));
        AgentContextManager contexts = new AgentContextManager(sessions, incidents, messages, records, routePort,
                instancePort, 8);
        TargetResolver targets = new TargetResolver(routePort, instancePort, TargetInterpreter.none());
        orchestrator = new AgentOrchestrator(sessions, incidents, messages, records, registry, contexts, targets,
                investigations);
    }

    @AfterEach
    void tearDown() {
        tasks.stop();
    }

    @Test
    void followUpQuestionReusesActiveIncidentAndTarget() {
        Session session = orchestrator.startSession("admin");

        AgentResponse first = send(session, "admin", "为什么 /api/demo/tt 调用失败？");
        AgentResponse second = send(session, "admin", "为什么没有实例？");

        assertNotNull(first.task());
        assertNotNull(second.task());
        // 第二轮没有提到任何对象，沿用同一事件与同一取数路径，而不是当成新问题。
        assertEquals(first.incident().incidentId(), second.incident().incidentId());
        assertEquals("/api/demo/tt", second.task().path());
        assertEquals(ResourceTarget.route("/api/demo/tt"), second.task().target());
        assertFalse(first.needsClarification());
        assertTrue(second.reply().content().startsWith("已继续调查"));
    }

    @Test
    void namingAnotherTargetOpensNewIncident() {
        Session session = orchestrator.startSession("admin");
        AgentResponse first = send(session, "admin", "为什么 /api/demo/tt 调用失败？");

        AgentResponse second = send(session, "admin", "payment-service 为什么这么慢？");

        assertNotNull(second.incident());
        assertTrue(second.incident().incidentId() != null
                && !second.incident().incidentId().equals(first.incident().incidentId()));
        assertEquals(ResourceTarget.service("payment-service"), second.task().target());
        assertEquals("/api/pay", second.task().path());
    }

    @Test
    void messageWithoutResolvableTargetAsksForClarificationAndCreatesNoTask() {
        Session session = orchestrator.startSession("admin");

        AgentResponse response = send(session, "admin", "帮我看看最近有没有问题");

        assertTrue(response.needsClarification());
        assertNull(response.task());
        assertNull(response.incident());
        assertTrue(response.clarification().contains("请求路径"));
        assertEquals(2, orchestrator.conversation(session.sessionId(), "admin").size());
    }

    @Test
    void conversationIsRecordedInOrderWithBothRoles() {
        Session session = orchestrator.startSession("admin");

        send(session, "admin", "为什么 /api/demo/tt 调用失败？");

        List<AgentMessage> conversation = orchestrator.conversation(session.sessionId(), "admin");
        assertEquals(2, conversation.size());
        assertEquals(MessageRole.USER, conversation.get(0).role());
        assertEquals(MessageRole.AGENT, conversation.get(1).role());
        // 首次提问生成会话标题，供会话列表展示。
        assertEquals("为什么 /api/demo/tt 调用失败？", orchestrator.session(session.sessionId(), "admin")
                .orElseThrow().title());
    }

    @Test
    void sessionsAreVisibleOnlyToTheirOwner() {
        Session session = orchestrator.startSession("admin");

        assertTrue(orchestrator.session(session.sessionId(), "other").isEmpty());
        assertTrue(orchestrator.sessions("other").isEmpty());
        assertTrue(orchestrator.send(session.sessionId(), "other", "为什么 /api/demo/tt 失败？",
                AgentRequestOptions.none()).isEmpty());
        assertEquals(1, orchestrator.sessions("admin").size());
    }

    @Test
    void taskLookupRespectsSessionOwnership() {
        Session session = orchestrator.startSession("admin");
        String taskId = send(session, "admin", "为什么 /api/demo/tt 调用失败？").task().taskId();

        assertTrue(orchestrator.task(taskId, "admin").isPresent());
        assertTrue(orchestrator.task(taskId, "other").isEmpty());
    }

    @Test
    void explicitAdvancedTargetIsUsedWhenQuestionNamesNothing() {
        Session session = orchestrator.startSession("admin");

        AgentResponse response = orchestrator.send(session.sessionId(), "admin", "它到底是哪里出了问题？",
                new AgentRequestOptions(ResourceTarget.service("demo-service"),
                        com.rover.agent.core.model.TimeRange.unspecified())).orElseThrow();

        assertEquals(TargetType.SERVICE, response.task().target().type());
        assertEquals("/api/demo/tt", response.task().path());
    }

    @Test
    void oneShotKeepsSingleInvestigationShape() {
        AgentResponse response = orchestrator.oneShot(null, ResourceTarget.route("/api/demo/tt"), "为什么失败？");

        assertNotNull(response.task());
        assertEquals(ResourceTarget.route("/api/demo/tt"), response.task().target());
        // 旧入口不带用户身份：会话同样没有归属，未启用登录时才可访问。
        assertNull(response.session().userId());
    }

    private AgentResponse send(Session session, String userId, String message) {
        return orchestrator.send(session.sessionId(), userId, message, AgentRequestOptions.none()).orElseThrow();
    }
}