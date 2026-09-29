package com.rover.agent.runtime.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.capability.CapabilityExecutor;
import com.rover.agent.core.capability.CapabilityRegistry;
import com.rover.agent.core.model.ActionStatus;
import com.rover.agent.core.model.AgentAction;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.SessionStatus;
import com.rover.agent.core.port.RouteChangePreview;
import com.rover.agent.core.port.RouteChangeResult;
import com.rover.agent.core.port.RouteControlPort;
import com.rover.agent.core.port.RouteControlRoute;
import com.rover.agent.core.port.RouteControlState;
import com.rover.agent.core.port.RouteControlTarget;
import com.rover.agent.core.port.RouteOperation;
import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.core.snapshot.RouteSnapshot;
import com.rover.agent.runtime.action.AgentActionService;
import com.rover.agent.runtime.action.RouteWeightActionExecutor;
import com.rover.agent.runtime.repository.InMemoryAgentActionRepository;
import com.rover.agent.runtime.repository.InMemoryAgentSessionRepository;
import com.rover.agent.runtime.task.InvestigationTask;
import com.rover.agent.runtime.task.InvestigationTaskRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 提案工具是模型能触达的最远处：它能写下的只有一条「待审批」记录。
 *
 * <p>假网关的写方法直接抛错，因此这条用例证明的不是「写请求次数是 0」，而是
 * <b>提案路径上根本不存在能走到写方法的代码</b>——把「模型不能直接改生产」从约定变成了可执行的断言。
 */
class OpsToolsProposalTest {

    private static final String SESSION = "s-proposal";

    @Test
    void proposalRegistersAnActionWithoutAnyWritePath() {
        InMemoryAgentActionRepository repository = new InMemoryAgentActionRepository(10);
        InMemoryAgentSessionRepository sessions = new InMemoryAgentSessionRepository(10);
        sessions.save(new Session(SESSION, "alice", "订单灰度", null, SessionStatus.ACTIVE, 1L, 1L, List.of()));
        UnwritableGateway gateway = new UnwritableGateway();
        AgentActionService service = new AgentActionService(repository, sessions, gateway,
                new RouteWeightActionExecutor(gateway, repository));
        InvestigationTask task = new InvestigationTaskRegistry().register(SESSION, "/api/order 的 v2 放量到 20");
        task.bind("i1", "/api/order", ResourceTarget.route("/api/order"));
        OpsTools tools = new OpsTools(executor(), task, service);

        String answer = tools.proposeTargetWeightChange("/api/order", "v2", 20, "RAW_WEIGHT");

        assertTrue(answer.contains("尚未执行"), answer);
        assertTrue(answer.contains("order-service@v2"), answer);
        assertTrue(answer.contains("5 → 20"), answer);
        assertTrue(answer.contains("预计流量占比"), answer);
        assertTrue(answer.contains("批准"), "必须告诉模型这一步要等人批准，否则它会对用户说「已经改好了」");

        List<AgentAction> saved = repository.bySession(SESSION);
        assertEquals(1, saved.size());
        AgentAction action = saved.get(0);
        assertEquals(ActionStatus.PENDING_APPROVAL, action.status());
        assertEquals(17, action.expectedRevision(), "提议要记下依据的版本，批准时才能比对");
        assertEquals(task.taskId(), action.taskId(), "变更要能回到提出它的那次对话");
        assertEquals("i1", action.incidentId(), "变更挂在它所属的事件下，右栏才能按事件聚合");
        assertEquals("alice", action.requestedBy(), "提议人来自会话归属，不来自模型");
    }

    @Test
    void proposalWithoutControlPortSaysSoInsteadOfPretending() {
        InvestigationTask task = new InvestigationTaskRegistry().register(SESSION, "放量");
        OpsTools tools = new OpsTools(executor(), task);

        String answer = tools.proposeTargetWeightChange("/api/order", "v2", 20, "RAW_WEIGHT");

        assertTrue(answer.contains("只读诊断"), answer);
    }

    /**
     * 「放量到 20」这种没有单位的表达必须变成澄清，而不是替用户挑一个单位。
     *
     * <p>这是 P4.1 的核心断言：20 在 95/5 的路由上既可能是权重 20，也可能是 20% 流量（≈2000），
     * 猜错就是一次数量级级别的事故，因此工具层必须让模型把问题抛回给用户。
     */
    @Test
    void ambiguousUnitAsksForClarificationInsteadOfGuessing() {
        InMemoryAgentActionRepository repository = new InMemoryAgentActionRepository(10);
        InMemoryAgentSessionRepository sessions = new InMemoryAgentSessionRepository(10);
        sessions.save(new Session(SESSION, "alice", "订单灰度", null, SessionStatus.ACTIVE, 1L, 1L, List.of()));
        UnwritableGateway gateway = new UnwritableGateway();
        AgentActionService service = new AgentActionService(repository, sessions, gateway,
                new RouteWeightActionExecutor(gateway, repository));
        InvestigationTask task = new InvestigationTaskRegistry().register(SESSION, "/api/order 的 v2 放量到 20");
        task.bind("i1", "/api/order", ResourceTarget.route("/api/order"));
        OpsTools tools = new OpsTools(executor(), task, service);

        String answer = tools.proposeTargetWeightChange("/api/order", "v2", 20, "UNSURE");

        assertTrue(answer.contains("澄清"), answer);
        assertTrue(answer.contains("没有创建变更计划"), answer);
        assertEquals(0, repository.bySession(SESSION).size(), "语义不明时一张卡都不能生成");
    }

    /** 百分比必须由代码换算：模型只给出「20%」，写进路由表的权重由 Java 算出来。 */
    @Test
    void trafficPercentIsConvertedToWeightByCode() {
        InMemoryAgentActionRepository repository = new InMemoryAgentActionRepository(10);
        InMemoryAgentSessionRepository sessions = new InMemoryAgentSessionRepository(10);
        sessions.save(new Session(SESSION, "alice", "订单灰度", null, SessionStatus.ACTIVE, 1L, 1L, List.of()));
        UnwritableGateway gateway = new UnwritableGateway();
        AgentActionService service = new AgentActionService(repository, sessions, gateway,
                new RouteWeightActionExecutor(gateway, repository));
        InvestigationTask task = new InvestigationTaskRegistry().register(SESSION, "v2 流量放到 20%");
        task.bind("i1", "/api/order", ResourceTarget.route("/api/order"));
        OpsTools tools = new OpsTools(executor(), task, service);

        String answer = tools.proposeTargetWeightChange("/api/order", "v2", 20, "TRAFFIC_PERCENT");

        List<AgentAction> saved = repository.bySession(SESSION);
        assertEquals(1, saved.size());
        AgentAction action = saved.get(0);
        // 其他版本合计 95，要占 20%：w' = 95 * 20 / 80 ≈ 24
        assertEquals(24, action.desiredWeight(), "百分比到权重的换算只能由代码完成");
        assertEquals(20, action.requestedValue(), "用户当初要的百分比要原样留着，事后才说得清批的是什么");
        assertEquals(5.0, action.beforeTrafficPercent(), 0.05);
        assertEquals(20.2, action.desiredTrafficPercent(), 0.05);
        assertTrue(answer.contains("流量占比 20%"), answer);
    }

    private static CapabilityExecutor executor() {
        return new CapabilityExecutor(new EmptyRoutes(), null, null, null, null, null, null, null,
                CapabilityRegistry.standard());
    }

    /** 空路由：提案只用可写端口取数，只读视图在这里不参与判断。 */
    private static final class EmptyRoutes implements com.rover.agent.core.port.RouteReadPort {

        @Override
        public List<RouteSnapshot> routes() {
            return List.of();
        }

        @Override
        public DiscoveryMode discoveryMode() {
            return DiscoveryMode.UNKNOWN;
        }
    }

    /**
     * 只读的假网关：任何写方法被调用都直接失败。
     *
     * <p>这是本用例的核心断言载体——只要提案路径上有人调用了写方法，测试就会炸，
     * 而不是靠数次数发现「多写了一次」。
     */
    private static final class UnwritableGateway implements RouteControlPort {

        @Override
        public RouteControlState state() {
            return new RouteControlState(17, "",
                    List.of(new RouteControlRoute("order-api", "/api/order", "",
                            List.of(new RouteControlTarget("order-service", "v1", 95),
                                    new RouteControlTarget("order-service", "v2", 5)))));
        }

        @Override
        public RouteChangePreview preview(String routeId, String serviceName, String group, int weight) {
            return new RouteChangePreview(17, "预览通过，未落盘、未生效",
                    List.of("MODIFIED /api/order：order-service@" + group + " 权重 → " + weight));
        }

        @Override
        public RouteChangeResult adjustTargetWeight(String routeId, String serviceName, String group, int weight,
                                                    int expectedRevision, String operationId) {
            throw new AssertionError("提议阶段不允许发出写请求");
        }

        @Override
        public RouteOperation operation(String operationId) {
            throw new AssertionError("提议阶段不需要回查操作结果");
        }
    }
}
