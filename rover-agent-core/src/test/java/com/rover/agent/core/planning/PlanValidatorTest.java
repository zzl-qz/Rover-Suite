package com.rover.agent.core.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.capability.CapabilityDescriptor;
import com.rover.agent.core.capability.CapabilityRegistry;
import com.rover.agent.core.model.ResourceTarget;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 计划校验与确定性规划：模型可以提建议，能不能执行由注册表与目标边界决定。 */
class PlanValidatorTest {

    private static final CapabilityRegistry REGISTRY = CapabilityRegistry.standard();

    private final PlanValidator validator = new PlanValidator(REGISTRY, PlanningLimits.defaults());

    private static PlanningRequest request(String path, ResourceTarget target) {
        return new PlanningRequest("为什么失败？", target, path, List.of(), List.of());
    }

    private static InvestigationPlan planOf(AgentCapability... capabilities) {
        List<PlannedStep> steps = java.util.Arrays.stream(capabilities)
                .map(capability -> PlannedStep.required(capability, "测试步骤", ResourceTarget.unknown()))
                .toList();
        return new InvestigationPlan("测试计划", List.of(), steps);
    }

    @Test
    void unregisteredOrUnconnectedCapabilityIsRejected() {
        // 把两个能力收口为「已登记但未接入适配器」：出现在计划里也必须被丢掉。
        PlanValidator partial = validatorWithout(AgentCapability.CONFIG_READ, AgentCapability.EVENT_QUERY);
        InvestigationPlan validated = partial.validate(planOf(AgentCapability.CONFIG_READ,
                AgentCapability.EVENT_QUERY), request("/api/demo/tt", ResourceTarget.route("/api/demo/tt")), Set.of());

        assertTrue(validated.isEmpty());
    }

    @Test
    void keepsOnlySelectableCapabilitiesFromMixedPlan() {
        PlanValidator partial = validatorWithout(AgentCapability.CONFIG_READ);
        InvestigationPlan validated = partial.validate(planOf(AgentCapability.ROUTE_QUERY,
                AgentCapability.CONFIG_READ, AgentCapability.INSTANCE_QUERY),
                request("/api/demo/tt", ResourceTarget.route("/api/demo/tt")), Set.of());

        assertEquals(List.of(AgentCapability.ROUTE_QUERY, AgentCapability.INSTANCE_QUERY),
                validated.steps().stream().map(PlannedStep::capability).toList());
    }

    /** 按「指定能力未接入」重建校验器：选择与执行边界由注册表统一收口。 */
    private static PlanValidator validatorWithout(AgentCapability... unavailable) {
        Set<AgentCapability> blocked = Set.of(unavailable);
        List<CapabilityDescriptor> descriptors = REGISTRY.all().stream()
                .map(item -> blocked.contains(item.id())
                        ? new CapabilityDescriptor(item.id(), item.name(), item.description(), item.risk(),
                                item.supportedTargetTypes(), false, item.stepType(), item.stepName())
                        : item)
                .toList();
        return new PlanValidator(new CapabilityRegistry(descriptors), PlanningLimits.defaults());
    }

    @Test
    void sameCapabilityIsKeptOnceAndSettledCapabilitiesAreDropped() {
        InvestigationPlan deduped = validator.validate(planOf(AgentCapability.ROUTE_QUERY, AgentCapability.ROUTE_QUERY),
                request("/api/demo/tt", ResourceTarget.route("/api/demo/tt")), Set.of());
        assertEquals(1, deduped.steps().size());

        InvestigationPlan settled = validator.validate(planOf(AgentCapability.ROUTE_QUERY, AgentCapability.INSTANCE_QUERY),
                request("/api/demo/tt", ResourceTarget.route("/api/demo/tt")), Set.of(AgentCapability.ROUTE_QUERY));
        assertEquals(List.of(AgentCapability.INSTANCE_QUERY),
                settled.steps().stream().map(PlannedStep::capability).toList());
    }

    @Test
    void stepTargetIsNormalisedToRequestTarget() {
        InvestigationPlan proposed = new InvestigationPlan("越权尝试", List.of(), List.of(
                PlannedStep.required(AgentCapability.INSTANCE_QUERY, "查别的服务", ResourceTarget.service("other-service"))));

        InvestigationPlan validated = validator.validate(proposed,
                request("/api/demo/tt", ResourceTarget.route("/api/demo/tt")), Set.of());

        assertEquals(ResourceTarget.route("/api/demo/tt"), validated.steps().get(0).target());
    }

    @Test
    void capabilityUnsupportedForTargetTypeIsDropped() {
        // 追踪查询以路径为口径：目标为未知对象时它对本轮没有价值。
        InvestigationPlan validated = validator.validate(planOf(AgentCapability.TRACE_QUERY),
                request("", ResourceTarget.unknown()), Set.of());

        assertTrue(validated.isEmpty());
    }

    @Test
    void planStepsAreTruncatedToConfiguredLimit() {
        PlanValidator limited = new PlanValidator(REGISTRY, new PlanningLimits(3, 10, 2));

        InvestigationPlan validated = limited.validate(planOf(AgentCapability.ROUTE_QUERY, AgentCapability.INSTANCE_QUERY,
                AgentCapability.GATEWAY_METRICS_QUERY, AgentCapability.TRACE_QUERY),
                request("/api/demo/tt", ResourceTarget.route("/api/demo/tt")), Set.of());

        assertEquals(2, validated.steps().size());
    }

    @Test
    void rulePlannerEmitsOnlySelectableCapabilities() {
        RuleBasedPlanner planner = new RuleBasedPlanner(REGISTRY);

        InvestigationPlan planned = planner.plan(request("/api/demo/tt", ResourceTarget.route("/api/demo/tt")));

        assertFalse(planned.isEmpty());
        assertTrue(REGISTRY.selectableCapabilities().containsAll(planned.capabilities()),
                "确定性规划器选了注册表里不存在或未开放的能力：" + planned.capabilities());
        assertFalse(planned.capabilities().contains(AgentCapability.CONFIG_READ));
        assertFalse(planned.capabilities().contains(AgentCapability.EVENT_QUERY));
    }

    @Test
    void rulePlannerWithoutPathPlansInstanceQueryOnly() {
        InvestigationPlan planned = new RuleBasedPlanner(REGISTRY)
                .plan(request("", ResourceTarget.service("demo-service")));

        assertEquals(List.of(AgentCapability.INSTANCE_QUERY),
                planned.steps().stream().map(PlannedStep::capability).toList());
    }
}