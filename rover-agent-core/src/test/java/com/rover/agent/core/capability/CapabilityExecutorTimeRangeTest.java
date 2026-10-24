package com.rover.agent.core.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.investigation.FindingsInput;
import com.rover.agent.core.investigation.InvestigationRules;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.TimeRange;
import com.rover.agent.core.model.Verdict;
import com.rover.agent.core.port.LogRequest;
import com.rover.agent.core.port.RouteReadPort;
import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.core.snapshot.GatewayMetricSnapshot;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RegistryEventSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import com.rover.agent.core.snapshot.TraceRow;
import com.rover.agent.core.snapshot.TraceSnapshot;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CapabilityExecutorTimeRangeTest {

    private static final String PATH = "/api/order";
    private static final TimeRange RANGE = new TimeRange(100_000L, 200_000L);

    @Test
    void plannedLogQueriesUseTheRequestedWindowRatherThanTheLastDay() {
        AtomicReference<LogRequest> requested = new AtomicReference<>();
        CapabilityExecutor executor = new CapabilityExecutor(null, null, null, null, null, null,
                request -> { requested.set(request); return List.of(); }, null, CapabilityRegistry.standard());

        CapabilityResult result = executor.execute(AgentCapability.LOG_QUERY, ResourceTarget.route(PATH), PATH,
                "task", null, RANGE);

        assertEquals(RANGE.fromMillis(), requested.get().from());
        assertEquals(RANGE.toMillis(), requested.get().to());
        assertEquals(PATH, requested.get().target());
        assertEquals("100000", result.evidence().get(0).metadata().get("requestedFromMillis"));
        assertEquals("REQUESTED_WINDOW", result.evidence().get(0).metadata().get("temporalScope"));
    }

    @Test
    void rollingMetricsCannotPretendToCoverAnAbsoluteTimeRange() {
        AtomicInteger reads = new AtomicInteger();
        CapabilityExecutor executor = new CapabilityExecutor(null, null,
                seconds -> { reads.incrementAndGet(); return new GatewayMetricSnapshot(100, 0, 0, 1L); },
                null, null, null, null, null, CapabilityRegistry.standard());

        CapabilityResult result = executor.execute(AgentCapability.GATEWAY_METRICS_QUERY,
                ResourceTarget.route(PATH), PATH, "task", null, RANGE);

        assertEquals(0, reads.get(), "不支持绝对窗口时不拿当前零错误冒充历史数据");
        assertTrue(result.evidence().isEmpty());
        assertTrue(String.join("", result.limitations()).contains("不能回溯"));
    }

    @Test
    void traceEvidenceAndVerdictsUseTheSameRequestedWindow() {
        TraceSnapshot snapshot = new TraceSnapshot(true, 0.5, List.of(
                new TraceRow("inside-503", PATH, 503, 150_000L),
                new TraceRow("outside-200", PATH, 200, 300_000L)), System.currentTimeMillis());
        CapabilityExecutor executor = new CapabilityExecutor(null, null, null, path -> snapshot, null, null,
                null, null, CapabilityRegistry.standard());

        CapabilityResult result = executor.execute(AgentCapability.TRACE_QUERY, ResourceTarget.route(PATH), PATH,
                "task", null, RANGE);
        assertEquals(List.of("inside-503"), result.traces().rows().stream().map(TraceRow::traceId).toList());
        assertTrue(result.evidence().get(0).summary().contains("inside-503"));
        assertFalse(result.evidence().get(0).summary().contains("outside-200"));
        var findings = InvestigationRules.evaluate(new FindingsInput(PATH, route(), true, DiscoveryMode.NAMESERVER,
                List.of(healthy()), result.traces(), null, RANGE));
        assertEquals(Verdict.CONFIRMED, findings.hypotheses().stream().filter(h -> h.id().equals("H5"))
                .findFirst().orElseThrow().status());
        assertTrue(findings.hypotheses().stream().filter(h -> !h.id().equals("H5"))
                .allMatch(h -> h.status() == Verdict.UNKNOWN));
    }

    @Test
    void currentHealthyStateAndOutOfWindowFailuresDoNotRefuteHistoricalFaults() {
        TraceSnapshot snapshot = new TraceSnapshot(true, 1.0,
                List.of(new TraceRow("current-503", PATH, 503, System.currentTimeMillis())),
                System.currentTimeMillis());
        var findings = InvestigationRules.evaluate(new FindingsInput(PATH, route(), true, DiscoveryMode.NAMESERVER,
                List.of(healthy()), snapshot, List.of(), RANGE));
        assertTrue(findings.hypotheses().stream().allMatch(h -> h.status() == Verdict.UNKNOWN));
        assertTrue(findings.summary().contains("不能据此判断"));

        RouteReadPort routes = new RouteReadPort() {
            public List<RouteSnapshot> routes() { return List.of(route()); }
            public DiscoveryMode discoveryMode() { return DiscoveryMode.NAMESERVER; }
        };
        CapabilityExecutor executor = new CapabilityExecutor(routes, null, null, null,
                null, null, null, null, CapabilityRegistry.standard());
        CapabilityResult current = executor.execute(AgentCapability.ROUTE_QUERY, ResourceTarget.route(PATH),
                PATH, "task", null, RANGE);
        assertTrue(current.evidence().stream().allMatch(e -> "CURRENT_CONTEXT".equals(e.metadata().get("temporalScope"))));
        assertTrue(current.evidence().get(0).summary().contains("不覆盖指定时段"));
    }

    @Test
    void registryEventNarrationFiltersAgainstTheRequestedWindow() {
        CapabilityExecutor executor = new CapabilityExecutor(null, null, null, null, null,
                () -> List.of(new RegistryEventSnapshot(150_000L, "REMOVED", "orders", "old", "in-window"),
                        new RegistryEventSnapshot(300_000L, "REGISTERED", "orders", "new", "out-of-window")),
                null, null, CapabilityRegistry.standard());
        CapabilityResult result = executor.execute(AgentCapability.EVENT_QUERY, ResourceTarget.route(PATH),
                PATH, "task", null, RANGE);
        assertTrue(result.evidence().get(0).summary().contains("in-window"));
        assertFalse(result.evidence().get(0).summary().contains("out-of-window"));
        assertEquals("1", result.evidence().get(0).metadata().get("sampleSize"));
    }

    @Test
    void invalidRangesFailAtTheSharedBoundary() {
        assertThrows(IllegalArgumentException.class, () -> new TimeRange(-1, 100));
        assertThrows(IllegalArgumentException.class, () -> new TimeRange(200, 100));
        assertTrue(TimeRange.unspecified().isUnspecified());
    }

    private static RouteSnapshot route() {
        return new RouteSnapshot("orders", PATH, "orders", "v1", "", "", 1L);
    }

    private static InstanceSnapshot healthy() {
        return new InstanceSnapshot("orders", "v1", "", "127.0.0.1", 8080, true, 100, true, 1L);
    }
}
