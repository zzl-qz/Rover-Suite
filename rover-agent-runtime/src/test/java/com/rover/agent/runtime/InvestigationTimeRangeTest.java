package com.rover.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.capability.CapabilityExecutor;
import com.rover.agent.core.capability.CapabilityRegistry;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.model.TimeRange;
import com.rover.agent.core.model.Verdict;
import com.rover.agent.core.planning.PlanValidator;
import com.rover.agent.core.planning.PlanningLimits;
import com.rover.agent.core.planning.RuleBasedPlanner;
import com.rover.agent.core.port.LogEntry;
import com.rover.agent.core.port.LogRequest;
import com.rover.agent.core.port.RouteReadPort;
import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.core.snapshot.GatewayMetricSnapshot;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import com.rover.agent.core.snapshot.TraceRow;
import com.rover.agent.core.snapshot.TraceSnapshot;
import com.rover.agent.runtime.journal.OpsJournal;
import com.rover.agent.runtime.llm.ModelExplainer;
import com.rover.agent.runtime.llm.NoopChatModelGateway;
import com.rover.agent.runtime.task.IncidentRegistry;
import com.rover.agent.runtime.task.InvestigationTaskRegistry;
import com.rover.agent.runtime.tool.OpsTools;
import com.rover.agent.runtime.tool.SnapshotTools;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class InvestigationTimeRangeTest {

    private static final String PATH = "/api/order";
    private static final TimeRange RANGE = new TimeRange(100_000L, 200_000L);

    @Test
    void authenticatedAlertCarriesItsWindowThroughPlanningToolsVerdictsAndExplanationSnapshots() throws Exception {
        AtomicReference<LogRequest> queried = new AtomicReference<>();
        AtomicInteger metricReads = new AtomicInteger();
        CapabilityExecutor executor = executor(queried, metricReads);
        IncidentRegistry registry = new IncidentRegistry();
        InvestigationTaskRegistry tasks = new InvestigationTaskRegistry();
        PlanningLimits limits = PlanningLimits.defaults();
        InvestigationService service = new InvestigationService(registry, tasks,
                new ModelExplainer(new NoopChatModelGateway()), new RuleBasedPlanner(CapabilityRegistry.standard()),
                new PlanValidator(CapabilityRegistry.standard(), limits), executor, limits, OpsJournal.none());
        try {
            TaskView submitted = service.submitAlert("alerts-service", PATH, "历史时段的 503", RANGE);
            TaskView done = await(tasks, submitted.taskId());

            assertEquals("COMPLETED", done.status().name());
            assertEquals(RANGE, registry.incident(done.incidentId()).orElseThrow().timeRange());
            assertEquals("alerts-service", registry.incident(done.incidentId()).orElseThrow().triggeredBy());
            assertEquals(RANGE.fromMillis(), queried.get().from());
            assertEquals(RANGE.toMillis(), queried.get().to());
            assertEquals(0, metricReads.get());
            assertTrue(done.result().hypotheses().stream().noneMatch(h -> h.status() == Verdict.REJECTED));
            assertEquals(Verdict.CONFIRMED, done.result().hypotheses().stream().filter(h -> h.id().equals("H5"))
                    .findFirst().orElseThrow().status());
            assertTrue(done.result().evidence().stream().allMatch(e -> "100000".equals(e.metadata().get("requestedFromMillis"))));
            assertTrue(new SnapshotTools(done.result().evidence()).coreSnapshots().get("历史日志").contains("historical-failure"),
                    "调查采到的时段日志必须进入解读上下文");
        } finally {
            tasks.stop();
        }
    }

    @Test
    void conversationToolsCannotReplaceAnIncidentWindowWithModelChosenHours() {
        AtomicReference<LogRequest> queried = new AtomicReference<>();
        AtomicInteger metricReads = new AtomicInteger();
        InvestigationTaskRegistry tasks = new InvestigationTaskRegistry();
        try {
            var task = tasks.register("session", "调查指定时段");
            task.bind(null, PATH, ResourceTarget.route(PATH), RANGE);
            OpsTools tools = new OpsTools(executor(queried, metricReads), task);
            tools.getRoute(PATH);
            tools.getTraces(PATH);
            tools.queryLogs("error", PATH, 1);
            String metricResult = tools.getGatewayMetrics(PATH);

            assertEquals(RANGE.fromMillis(), queried.get().from());
            assertEquals(RANGE.toMillis(), queried.get().to());
            assertEquals(0, metricReads.get());
            assertTrue(metricResult.contains("不能回溯"));
            assertTrue(tools.judge().hypotheses().stream().noneMatch(h -> h.status() == Verdict.REJECTED));
            assertFalse(tools.evidence().stream().anyMatch(e -> e.summary().contains("current-only")));
        } finally {
            tasks.stop();
        }
    }

    private static CapabilityExecutor executor(AtomicReference<LogRequest> queried, AtomicInteger metricReads) {
        return new CapabilityExecutor(
                new RouteReadPort() {
                    public List<RouteSnapshot> routes() {
                        return List.of(new RouteSnapshot("orders", PATH, "orders", "v1", "", "", 1L));
                    }
                    public DiscoveryMode discoveryMode() { return DiscoveryMode.NAMESERVER; }
                },
                () -> List.of(new InstanceSnapshot("orders", "v1", "", "127.0.0.1", 8080, true, 100, true, 1L)),
                seconds -> { metricReads.incrementAndGet(); return new GatewayMetricSnapshot(100, 0, 0, 1L); },
                path -> new TraceSnapshot(true, 1.0, List.of(
                        new TraceRow("historical-503", PATH, 503, 150_000L),
                        new TraceRow("current-only", PATH, 200, System.currentTimeMillis())),
                        System.currentTimeMillis()),
                () -> List.of(), () -> List.of(),
                request -> { queried.set(request); return List.of(new LogEntry(150_000L, "ERROR", PATH, "historical-failure")); },
                (query, limit) -> List.of(), CapabilityRegistry.standard());
    }

    private static TaskView await(InvestigationTaskRegistry tasks, String taskId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000L;
        TaskView view;
        do {
            view = tasks.get(taskId);
            if (!view.status().active()) {
                return view;
            }
            Thread.sleep(10L);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("调查未结束：" + view.status());
    }
}
