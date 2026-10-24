package com.rover.agent.runtime.graph;

import static org.junit.jupiter.api.Assertions.*;
import com.rover.agent.core.capability.*;
import com.rover.agent.core.model.*;
import com.rover.agent.core.planning.*;
import com.rover.agent.core.port.*;
import com.rover.agent.core.snapshot.*;
import com.rover.agent.runtime.repository.JdbcAgentStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HypothesisLoopTest {
    @TempDir Path temp;
    private static final String PATH = "/orders";
    private final CapabilityRegistry registry = CapabilityRegistry.standard();
    private final PlanningLimits limits = PlanningLimits.defaults();
    private final AtomicInteger routes = new AtomicInteger();
    private final AtomicInteger instances = new AtomicInteger();

    @Test void retriesTransientFailureOnceAndFollowsEvidenceGaps() {
        Recorder recorder = new Recorder();
        var outcome = graph(recorder, 1, false).investigate("t", PATH, "排查故障", ResourceTarget.route(PATH));
        assertEquals(2, instances.get());
        assertEquals(1, routes.get(), "成功查询不重复读取");
        assertEquals(3, outcome.rounds(), "实例补查成功后出现追踪缺口，下一轮改查日志");
        assertEquals(Verdict.REJECTED, outcome.findings().hypotheses().stream().filter(h -> h.id().equals("H3")).findFirst().orElseThrow().status());
        assertTrue(outcome.executedCapabilities().contains(AgentCapability.LOG_QUERY), "追踪无证据后必须改查其他来源");
        assertTrue(recorder.plan.progress().stopReason().contains("无法确认"));
        assertEquals(2, recorder.plan.progress().attempts().stream().filter(a -> a.capability() == AgentCapability.INSTANCE_QUERY).count());
    }

    @Test void outageStopsAfterTwoAttemptsAndKeepsUnknownAsAValidOutcome() {
        Recorder recorder = new Recorder();
        var outcome = graph(recorder, 100, false).investigate("t", PATH, "排查故障", ResourceTarget.route(PATH));
        assertEquals(2, instances.get());
        var h = outcome.findings().hypotheses().stream().filter(item -> item.id().equals("H3")).findFirst().orElseThrow();
        assertEquals(Verdict.UNKNOWN, h.status());
        assertFalse(h.missingEvidence().isEmpty());
        assertTrue(h.supportingEvidenceIds().isEmpty());
        assertTrue(outcome.toolCalls() <= limits.maxToolCalls());
    }

    @Test void transientRouteFailureDefersInstanceReadUntilTheRouteIsKnown() {
        Recorder recorder = new Recorder();
        RouteReadPort port = new RouteReadPort() {
            public List<RouteSnapshot> routes() {
                if (routes.incrementAndGet() == 1) { throw new SnapshotUnavailableException("timeout", new java.net.ConnectException()); }
                return List.of(new RouteSnapshot("r", PATH, "orders", "", "", "", System.currentTimeMillis()));
            }
            public DiscoveryMode discoveryMode() { return DiscoveryMode.NAMESERVER; }
        };
        InstanceReadPort instancePort = () -> {
            assertEquals(2, routes.get(), "路由读取成功之前不能用无范围实例判断该路径");
            instances.incrementAndGet(); return List.of();
        };
        CapabilityExecutor executor = new CapabilityExecutor(port, instancePort,
                seconds -> new GatewayMetricSnapshot(0,0,0,System.currentTimeMillis()),
                path -> new TraceSnapshot(false,0,List.of(),System.currentTimeMillis()), () -> List.of(), () -> List.of(),
                query -> List.of(), (query, limit) -> List.of(), registry);
        // 禁止指标内部的独立路由补读，单独验证路由依赖与补查顺序。
        InvestigationPlanner planner = new InvestigationPlanner() {
            public InvestigationPlan plan(PlanningRequest r) { return new InvestigationPlan("route dependency", List.of(), List.of(
                    PlannedStep.required(AgentCapability.ROUTE_QUERY,"route",ResourceTarget.route(PATH)),
                    PlannedStep.required(AgentCapability.INSTANCE_QUERY,"instance",ResourceTarget.route(PATH)))); }
            public PlanningDecision evaluate(PlanningRequest r, PlanProgress p) {
                return p.settled().contains(AgentCapability.INSTANCE_QUERY) ? PlanningDecision.finish("done") : PlanningDecision.continuePlanning("retry");
            }
        };
        new DynamicInvestigationGraph(planner, new PlanValidator(registry, limits), executor, limits, recorder)
                .investigate("t", PATH, "排查故障", ResourceTarget.route(PATH));
        assertEquals(2, routes.get());
        assertEquals(1, instances.get());
        assertTrue(recorder.plan.progress().attempts().stream().noneMatch(a -> a.capability() == AgentCapability.INSTANCE_QUERY
                && a.outcome() == CapabilityAttempt.Outcome.SKIPPED));
    }

    @Test void validEmptyRegistryIsNotTreatedAsATransientFailure() {
        var outcome = graph(new Recorder(), 0, true).investigate("t", PATH, "排查故障", ResourceTarget.route(PATH));
        assertEquals(1, instances.get());
        var h = outcome.findings().hypotheses().stream().filter(item -> item.id().equals("H3")).findFirst().orElseThrow();
        assertEquals(Verdict.CONFIRMED, h.status());
        assertEquals(1, h.supportingEvidenceIds().size());
    }

    @Test void h2RestartRetainsAttemptsFactsAndBudgetWithoutReReadingSuccessfulRoute() {
        String database = temp.resolve("agent").toString();
        try (JdbcAgentStore store = new JdbcAgentStore(database)) {
            store.sessions().save(new Session("s", "alice", "test", null, SessionStatus.ACTIVE, 1, 1, List.of()));
            Recorder interrupted = new Recorder() {
                @Override public void checkpoint(CheckpointStage stage, int rounds, int calls, String node) {
                    if (stage == CheckpointStage.TOOL_COMPLETED && calls == 2) {
                        TaskView task = new TaskView("t", "s", null, TaskStatus.RUNNING, AgentStepType.PLANNING, PATH,
                                ResourceTarget.route(PATH), "排查故障", 1, 0, List.of(), null, null, null,
                                TaskType.INVESTIGATION, plan, List.of(), List.of(), evidence);
                        store.checkpoints().save(task, new AgentCheckpoint("cp", "t", 1, stage, rounds, calls, 0,
                                evidence.size(), "", node, 10));
                        throw new IllegalStateException("simulated process interruption");
                    }
                }
            };
            assertThrows(IllegalStateException.class, () -> graph(interrupted, 1, false).investigate("t", PATH, "排查故障", ResourceTarget.route(PATH)));
        }
        try (JdbcAgentStore store = new JdbcAgentStore(database)) {
            TaskView task = store.tasks().find("t").orElseThrow();
            assertEquals(2, task.plan().progress().toolCalls());
            assertEquals(2, task.plan().progress().attempts().size());
            assertEquals(2, task.plan().progress().remainingSteps());
            assertFalse(task.plan().progress().limitations().isEmpty());
            assertNotNull(task.plan().progress().facts().route());
            Recorder resumed = new Recorder();
            resumed.plan = task.plan(); resumed.evidence.addAll(task.evidence());
            var outcome = graph(resumed, 1, false).investigate("t", PATH, "排查故障", ResourceTarget.route(PATH));
            assertEquals(1, routes.get());
            assertEquals(2, instances.get(), "重启后仅使用剩下的一次补查机会");
            assertTrue(outcome.toolCalls() >= 2);
            assertEquals(3, outcome.rounds(), "恢复继续未完成计划，不额外消耗一次规划轮数");
            assertEquals(outcome.evidence().size(), outcome.evidence().stream().map(Evidence::evidenceId).distinct().count());
            assertTrue(outcome.findings().hypotheses().stream().anyMatch(h -> h.id().equals("H1") && h.status() == Verdict.REJECTED));
        }
    }

    @Test void replayingTheSameSuccessfulPlanStopsWithoutNewToolCalls() {
        Recorder recorder = new Recorder();
        InvestigationPlanner repeating = new InvestigationPlanner() {
            public InvestigationPlan plan(PlanningRequest r) { return new InvestigationPlan("repeat", List.of(),
                    List.of(PlannedStep.required(AgentCapability.ROUTE_QUERY, "repeat", ResourceTarget.route(PATH)))); }
            public PlanningDecision evaluate(PlanningRequest r, PlanProgress p) { return PlanningDecision.continuePlanning("repeat"); }
        };
        var outcome = new DynamicInvestigationGraph(repeating, new PlanValidator(registry, limits), executor(0, false), limits, recorder)
                .investigate("t", PATH, "排查", ResourceTarget.route(PATH));
        assertEquals(1, routes.get());
        assertEquals(1, outcome.toolCalls());
        assertTrue(recorder.plan.progress().stopReason().contains("无信息重复"));
    }

    @Test void persistedDecisionFactsDoNotContainStaticUpstreamCredentials() {
        Recorder recorder = new Recorder();
        RouteReadPort port = new RouteReadPort() {
            public List<RouteSnapshot> routes() { return List.of(new RouteSnapshot("r", PATH, "", "", "https://user:password@host/?token=secret", "", 1)); }
            public DiscoveryMode discoveryMode() { return DiscoveryMode.UNKNOWN; }
        };
        CapabilityExecutor executor = new CapabilityExecutor(port, () -> List.of(), null, null, null, null, null, null, registry);
        InvestigationPlanner planner = new InvestigationPlanner() {
            public InvestigationPlan plan(PlanningRequest r) { return new InvestigationPlan("read", List.of(),
                    List.of(PlannedStep.required(AgentCapability.ROUTE_QUERY,"read",ResourceTarget.route(PATH)))); }
            public PlanningDecision evaluate(PlanningRequest r, PlanProgress p) { return PlanningDecision.finish("done"); }
        };
        new DynamicInvestigationGraph(planner, new PlanValidator(registry, limits), executor, limits, recorder)
                .investigate("t", PATH, "read", ResourceTarget.route(PATH));
        assertEquals("", recorder.plan.progress().facts().route().targetUrl());
        assertEquals("", recorder.plan.progress().facts().route().targetUrls());
    }

    private DynamicInvestigationGraph graph(Recorder recorder, int failures, boolean empty) {
        return new DynamicInvestigationGraph(new RuleBasedPlanner(registry), new PlanValidator(registry, limits), executor(failures, empty), limits, recorder);
    }
    private CapabilityExecutor executor(int failures, boolean empty) {
        RouteReadPort routePort = new RouteReadPort() {
            public List<RouteSnapshot> routes() { routes.incrementAndGet(); return List.of(new RouteSnapshot("r", PATH, "orders", "", "", "", System.currentTimeMillis())); }
            public DiscoveryMode discoveryMode() { return DiscoveryMode.NAMESERVER; }
        };
        InstanceReadPort instancePort = () -> {
            if (instances.incrementAndGet() <= failures) { throw new SnapshotUnavailableException("timeout", new java.net.SocketTimeoutException()); }
            return empty ? List.of() : List.of(new InstanceSnapshot("orders", "", "i1", "host", 80, true, 100, true, 0));
        };
        return new CapabilityExecutor(routePort, instancePort, seconds -> new GatewayMetricSnapshot(0,0,0,System.currentTimeMillis()),
                path -> new TraceSnapshot(false,0,List.of(),System.currentTimeMillis()), () -> List.of(), () -> List.of(),
                query -> List.of(), (query, limit) -> List.of(), registry);
    }
    private static class Recorder implements InvestigationReporter {
        InvestigationPlan plan = InvestigationPlan.empty();
        final List<Evidence> evidence = new ArrayList<>();
        public InvestigationPlan resumePlan() { return plan; }
        public List<Evidence> resumeEvidence() { return List.copyOf(evidence); }
        public void reportPlan(InvestigationPlan value) { plan = value; }
        public void reportCapabilityExecuted(AgentCapability capability) { }
        public void recordEvidence(List<Evidence> value) { evidence.addAll(value); }
        public void checkpoint(CheckpointStage stage, int rounds, int calls, String node) { }
        public void step(AgentStepType type, String name, StepStatus status, String detail) { }
    }
}
