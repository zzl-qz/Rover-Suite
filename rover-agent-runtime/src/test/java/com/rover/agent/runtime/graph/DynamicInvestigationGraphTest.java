package com.rover.agent.runtime.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.capability.CapabilityDescriptor;
import com.rover.agent.core.capability.CapabilityExecutor;
import com.rover.agent.core.capability.CapabilityRegistry;
import com.rover.agent.core.model.AgentStepType;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.StepStatus;
import com.rover.agent.core.planning.InvestigationPlan;
import com.rover.agent.core.planning.InvestigationPlanner;
import com.rover.agent.core.planning.PlanProgress;
import com.rover.agent.core.planning.PlanValidator;
import com.rover.agent.core.planning.PlannedStep;
import com.rover.agent.core.planning.PlanningDecision;
import com.rover.agent.core.planning.PlanningLimits;
import com.rover.agent.core.planning.PlanningRequest;
import com.rover.agent.core.port.InstanceReadPort;
import com.rover.agent.core.port.MetricReadPort;
import com.rover.agent.core.port.RouteReadPort;
import com.rover.agent.core.port.TraceReadPort;
import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.core.snapshot.GatewayMetricSnapshot;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import com.rover.agent.core.snapshot.TraceSnapshot;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 动态调查图的硬边界：轮数、能力调用次数与能力白名单。
 *
 * Planner 是可以被替换的（模型建议也走同一条路），因此这里的计划完全由测试脚本给出——
 * 无论 Planner 提出什么，图都必须只执行注册表里已接入的只读能力，并在触到上限时如实记录判断边界。
 */
class DynamicInvestigationGraphTest {

    private static final String PATH = "/api/demo/tt";
    private static final ResourceTarget TARGET = ResourceTarget.route(PATH);
    private static final RouteSnapshot DEMO_ROUTE =
            new RouteSnapshot("r1", PATH, "demo-service", "", "", 1L);

    @Test
    void stopsAtPlanningRoundLimitAndRecordsLimitation() {
        PlanningLimits limits = new PlanningLimits(2, 10, 6);
        ScriptedPlanner planner = new ScriptedPlanner(
                List.of(plan(AgentCapability.ROUTE_QUERY), plan(AgentCapability.INSTANCE_QUERY)),
                PlanningDecision.continuePlanning("测试：始终认为还需要继续"));
        RecordingReporter reporter = new RecordingReporter();

        InvestigationOutcome outcome = investigate(planner, limits, reporter);

        // Planner 一直要求继续，但图在第 2 轮后强制收尾，不允许无限重规划。
        assertEquals(2, outcome.rounds());
        assertEquals(2, outcome.toolCalls());
        assertEquals(List.of(AgentCapability.ROUTE_QUERY, AgentCapability.INSTANCE_QUERY),
                outcome.executedCapabilities());
        assertTrue(outcome.limitations().stream().anyMatch(item -> item.contains("规划轮数或能力调用上限")),
                "触到轮数上限时必须留下判断边界，实际为 " + outcome.limitations());
        assertTrue(reporter.details().stream().anyMatch(item -> item.contains("已达到规划上限（轮数 2/2")),
                "规划上限必须上报为步骤，实际为 " + reporter.details());
    }

    @Test
    void stopsAtToolCallLimitWithoutExecutingRemainingSteps() {
        PlanningLimits limits = new PlanningLimits(5, 2, 6);
        ScriptedPlanner planner = new ScriptedPlanner(
                List.of(plan(AgentCapability.ROUTE_QUERY, AgentCapability.INSTANCE_QUERY,
                        AgentCapability.GATEWAY_METRICS_QUERY)),
                PlanningDecision.continuePlanning("测试：始终认为还需要继续"));
        RecordingReporter reporter = new RecordingReporter();
        AtomicInteger metricReads = new AtomicInteger();

        InvestigationOutcome outcome = investigateWithCounter(planner, limits, reporter, metricReads);

        // 单轮计划有 3 步，但调用次数上限为 2：第 3 步不再发起调用，而不是超限执行。
        assertEquals(2, outcome.toolCalls());
        assertEquals(0, metricReads.get(), "触顶后的步骤不允许再发起任何能力调用");
        assertEquals(List.of(AgentCapability.ROUTE_QUERY, AgentCapability.INSTANCE_QUERY),
                outcome.executedCapabilities());
        assertTrue(outcome.limitations().stream().anyMatch(item -> item.contains("能力调用次数上限")),
                "触到调用上限时必须留下判断边界，实际为 " + outcome.limitations());
        assertTrue(reporter.details().stream().anyMatch(item -> item.contains("未执行：GATEWAY_METRICS_QUERY")),
                "未执行的步骤必须如实上报，实际为 " + reporter.details());
    }

    @Test
    void onlyExecutesCapabilitiesRegisteredAsSelectable() {
        PlanningLimits limits = PlanningLimits.defaults();
        // 注册表里 CONFIG_READ / EVENT_QUERY 被标为「已登记但未接入」：校验层丢弃它们，图不会执行。
        CapabilityRegistry registry = registryWithout(AgentCapability.CONFIG_READ, AgentCapability.EVENT_QUERY);
        ScriptedPlanner planner = new ScriptedPlanner(
                List.of(plan(AgentCapability.CONFIG_READ, AgentCapability.ROUTE_QUERY,
                        AgentCapability.EVENT_QUERY, AgentCapability.INSTANCE_QUERY)),
                PlanningDecision.finish("测试：本轮即可收尾"));
        RecordingReporter reporter = new RecordingReporter();

        InvestigationOutcome outcome = investigateWithRegistry(planner, limits, reporter, registry,
                new AtomicInteger());

        assertEquals(List.of(AgentCapability.ROUTE_QUERY, AgentCapability.INSTANCE_QUERY),
                outcome.executedCapabilities());
        assertFalse(outcome.plan().capabilities().contains(AgentCapability.CONFIG_READ));
        assertFalse(outcome.plan().capabilities().contains(AgentCapability.EVENT_QUERY));
        assertEquals(outcome.executedCapabilities(), reporter.capabilities());
    }

    /** 把指定能力标为「已登记但未接入」：这是 Planner 与执行层共同的硬边界。 */
    private static CapabilityRegistry registryWithout(AgentCapability... unavailable) {
        java.util.Set<AgentCapability> blocked = java.util.Set.of(unavailable);
        List<CapabilityDescriptor> descriptors = CapabilityRegistry.standard().all().stream()
                .map(item -> blocked.contains(item.id())
                        ? new CapabilityDescriptor(item.id(), item.name(), item.description(), item.risk(),
                                item.supportedTargetTypes(), false, item.stepType(), item.stepName())
                        : item)
                .toList();
        return new CapabilityRegistry(descriptors);
    }

    private static InvestigationOutcome investigate(InvestigationPlanner planner, PlanningLimits limits,
                                                    RecordingReporter reporter) {
        return investigateWithCounter(planner, limits, reporter, new AtomicInteger());
    }

    private static InvestigationOutcome investigateWithCounter(InvestigationPlanner planner, PlanningLimits limits,
                                                                RecordingReporter reporter,
                                                                AtomicInteger metricReads) {
        return investigateWithRegistry(planner, limits, reporter, CapabilityRegistry.standard(), metricReads);
    }

    private static InvestigationOutcome investigateWithRegistry(InvestigationPlanner planner, PlanningLimits limits,
                                                                 RecordingReporter reporter,
                                                                 CapabilityRegistry registry,
                                                                 AtomicInteger metricReads) {
        MetricReadPort metrics = windowSeconds -> {
            metricReads.incrementAndGet();
            return new GatewayMetricSnapshot(10, 0, 0, System.currentTimeMillis());
        };
        CapabilityExecutor executor = new CapabilityExecutor(routes(), instances(), metrics, traces(),
                () -> List.of(), () -> List.of(), registry);
        DynamicInvestigationGraph graph = new DynamicInvestigationGraph(planner,
                new PlanValidator(registry, limits), executor, limits, reporter);
        return graph.investigate("task-1", PATH, "为什么 " + PATH + " 调用失败？", TARGET, null);
    }

    private static RouteReadPort routes() {
        return new RouteReadPort() {
            @Override
            public List<RouteSnapshot> routes() {
                return List.of(DEMO_ROUTE);
            }

            @Override
            public DiscoveryMode discoveryMode() {
                return DiscoveryMode.NAMESERVER;
            }
        };
    }

    private static InstanceReadPort instances() {
        return () -> List.of(new InstanceSnapshot("demo-service", "", "", "10.0.0.7", 8080, true, 100, true, 0L));
    }

    private static TraceReadPort traces() {
        return path -> new TraceSnapshot(false, 0.0, List.of(), System.currentTimeMillis());
    }

    private static InvestigationPlan plan(AgentCapability... capabilities) {
        List<PlannedStep> steps = new java.util.ArrayList<>();
        for (AgentCapability capability : capabilities) {
            steps.add(PlannedStep.required(capability, "测试计划依据：" + capability, TARGET));
        }
        return new InvestigationPlan("测试计划目标", List.of("测试候选原因"), steps);
    }

    /** 按脚本逐轮返回计划的 Planner：评估结论固定，用于验证图自身的边界而不是 Planner 的判定。 */
    private static final class ScriptedPlanner implements InvestigationPlanner {

        private final List<InvestigationPlan> plans;
        private final PlanningDecision decision;
        private int index;

        private ScriptedPlanner(List<InvestigationPlan> plans, PlanningDecision decision) {
            this.plans = List.copyOf(plans);
            this.decision = decision;
        }

        @Override
        public InvestigationPlan plan(PlanningRequest request) {
            if (plans.isEmpty()) {
                return InvestigationPlan.empty();
            }
            int current = Math.min(index, plans.size() - 1);
            index++;
            return plans.get(current);
        }

        @Override
        public PlanningDecision evaluate(PlanningRequest request, PlanProgress progress) {
            return decision;
        }
    }

    /** 上报口的内存记录：计划、已执行能力与步骤说明都要能被断言。 */
    private static final class RecordingReporter implements InvestigationReporter {

        private final List<InvestigationPlan> plans = new CopyOnWriteArrayList<>();
        private final List<AgentCapability> capabilities = new CopyOnWriteArrayList<>();
        private final List<String> details = new CopyOnWriteArrayList<>();

        @Override
        public void reportPlan(InvestigationPlan plan) {
            plans.add(plan);
        }

        @Override
        public void reportCapabilityExecuted(AgentCapability capability) {
            capabilities.add(capability);
        }

        @Override
        public void step(AgentStepType type, String name, StepStatus status, String detail) {
            details.add(type + "|" + name + "|" + status + "|" + detail);
        }

        private List<String> details() {
            return details;
        }

        private List<AgentCapability> capabilities() {
            return capabilities;
        }

        @SuppressWarnings("unused")
        private List<InvestigationPlan> plans() {
            return plans;
        }
    }
}