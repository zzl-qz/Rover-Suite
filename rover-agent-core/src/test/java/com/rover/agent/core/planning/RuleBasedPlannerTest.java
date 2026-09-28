package com.rover.agent.core.planning;

import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.capability.CapabilityRegistry;
import com.rover.agent.core.model.ResourceTarget;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 确定性规划器的能力选择：「该调用哪些工具」这层里唯一不依赖模型的部分。
 *
 * 目标解析评测证明「找对对象」，工具选择评测证明「调对工具」，而这里是两者的地基：
 * 模型没参与、或模型的规划被整条丢弃时，靠的就是这份映射。映射被悄悄改宽（每次都读配置与事件）
 * 或改窄（漏掉必查项）都不会报错，只会让调查变慢、或让结论缺依据，因此固定下来。
 */
class RuleBasedPlannerTest {

    private final RuleBasedPlanner planner = new RuleBasedPlanner(CapabilityRegistry.standard());

    @Test
    void pathTargetRequiresRouteInstanceAndTrace() {
        PlanningRequest request = request("为什么 /api/order 调用失败", ResourceTarget.route("/api/order"), "/api/order");

        assertEquals(Set.of(AgentCapability.ROUTE_QUERY, AgentCapability.INSTANCE_QUERY, AgentCapability.TRACE_QUERY),
                planner.requiredCapabilities(request), "有请求路径时，路由、实例、追踪缺任何一项结论都站不住");
        assertTrue(planner.plan(request).capabilities().contains(AgentCapability.GATEWAY_METRICS_QUERY),
                "指标是补充项：有流量事实更好，没有也能下结论");
    }

    @Test
    void serviceTargetWithoutPathSkipsRouteAndTrace() {
        PlanningRequest request = request("demo-service 有几个健康实例", ResourceTarget.service("demo-service"), "");

        assertEquals(Set.of(AgentCapability.INSTANCE_QUERY), planner.requiredCapabilities(request));
        Set<AgentCapability> planned = planner.plan(request).capabilities();
        assertFalse(planned.contains(AgentCapability.ROUTE_QUERY), "没有请求路径却去查路由，取回来的只能是噪声");
        assertFalse(planned.contains(AgentCapability.TRACE_QUERY), "追踪同样以路径为取数口径");
    }

    @Test
    void unknownTargetWithoutPathPlansNothing() {
        assertTrue(planner.plan(request("帮我看看", ResourceTarget.unknown(), "")).isEmpty(),
                "目标与路径都不足时不猜：宁可转澄清，也不拿猜来的对象去取数");
    }

    @Test
    void configAndEventQuestionsAddTheirOwnStepOnly() {
        assertTrue(planner.plan(request("当前限流阈值是多少", ResourceTarget.service("demo-service"), ""))
                .capabilities().contains(AgentCapability.CONFIG_READ), "问到配置才读配置");
        assertTrue(planner.plan(request("最近有哪些实例上下线", ResourceTarget.service("demo-service"), ""))
                .capabilities().contains(AgentCapability.EVENT_QUERY), "问到事件才查事件");
        assertFalse(planner.plan(request("demo-service 有几个健康实例", ResourceTarget.service("demo-service"), ""))
                .capabilities().contains(AgentCapability.CONFIG_READ),
                "不问配置就不读：每次多两跳只读调用，证据里也会混进与问题无关的内容");
    }

    /**
     * 线索是提示而不是裁决：它只决定「要不要多看一个方向」，既不替问题定性，也不屏蔽另一条路。
     *
     * 这四条是给 {@code InvestigationCueDetector} 的回归基线。词表可以扩，但这几条语义不能被改坏——
     * 一旦有人把「先命中的方向说了算」写回来（旧 Intent 词表就是这么做的），下面第三条会立刻变红：
     *
     * <pre>
     * 「最近延迟为什么升高？」        → 不该仅因为「延迟」就去读配置（延迟由指标与追踪回答）
     * 「最近是不是有人改了超时配置？」  → 应该把 CONFIG_READ 纳入考虑
     * 「5xx 突然升高，限流阈值是不是改了？」→ METRICS 与 CONFIG 两个方向都合理
     * 「最近发生过什么配置变更？」     → 配置与变更经过都该看
     * </pre>
     */
    @Test
    void questionCuesHintDirectionsWithoutDecidingThem() {
        Set<AgentCapability> latency = planner
                .plan(request("最近延迟为什么升高？", ResourceTarget.route("/api/order"), "/api/order"))
                .capabilities();
        assertFalse(latency.contains(AgentCapability.CONFIG_READ), "「延迟」不是配置线索：指标与追踪才回答这个问题");
        assertFalse(latency.contains(AgentCapability.EVENT_QUERY), "「延迟」也不是变更经过线索");

        Set<AgentCapability> timeout = planner
                .plan(request("最近是不是有人改了超时配置？", ResourceTarget.route("/api/order"), "/api/order"))
                .capabilities();
        assertTrue(timeout.contains(AgentCapability.CONFIG_READ), "问到超时/配置本身，配置读取必须被考虑");

        Set<AgentCapability> threshold = planner
                .plan(request("5xx 突然升高，限流阈值是不是改了？", ResourceTarget.route("/api/order"), "/api/order"))
                .capabilities();
        assertTrue(threshold.contains(AgentCapability.GATEWAY_METRICS_QUERY), "流量事实与配置事实同时在问题里");
        assertTrue(threshold.contains(AgentCapability.CONFIG_READ), "命中配置线索不得屏蔽指标方向，反之亦然");

        Set<AgentCapability> changed = planner
                .plan(request("最近发生过什么配置变更？", ResourceTarget.service("demo-service"), ""))
                .capabilities();
        assertTrue(changed.contains(AgentCapability.CONFIG_READ), "「配置」指向当前生效配置");
        assertTrue(changed.contains(AgentCapability.EVENT_QUERY), "「变更」指向变更经过，两个方向都该被听见");
    }

    @Test
    void plannedCapabilitiesStayInsideTheReadOnlyRegistry() {
        CapabilityRegistry registry = CapabilityRegistry.standard();
        for (String path : List.of("/api/order", "")) {
            Set<AgentCapability> planned = planner
                    .plan(request("为什么调用失败，当前限流配置是多少", ResourceTarget.route("/api/order"), path))
                    .capabilities();
            assertTrue(registry.selectableCapabilities().containsAll(planned),
                    "规划出的能力必须都在注册表里：出现越界能力就等于模型凭空造出了工具");
        }
    }

    @Test
    void evaluationStopsOnceRequiredFactsAreSettled() {
        PlanningRequest request = request("为什么 /api/order 调用失败", ResourceTarget.route("/api/order"), "/api/order");

        assertTrue(planner.evaluate(request, PlanProgress.empty()).shouldContinue(), "一条事实都没采到就收尾属于漏查");
        assertFalse(planner.evaluate(request, PlanProgress.of(1, 3, Set.of(AgentCapability.ROUTE_QUERY,
                AgentCapability.INSTANCE_QUERY, AgentCapability.TRACE_QUERY), 0)).shouldContinue(),
                "必查事实齐了就收尾，不为可选项再跑一轮");
    }

    private PlanningRequest request(String question, ResourceTarget target, String path) {
        return new PlanningRequest(question, target, path, List.of(), List.of());
    }
}
