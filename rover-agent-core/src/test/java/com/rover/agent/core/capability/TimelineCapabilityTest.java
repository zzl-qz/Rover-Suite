package com.rover.agent.core.capability;

import com.rover.agent.core.model.*;
import com.rover.agent.core.timeline.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TimelineCapabilityTest {
    @Test void timelineRetainsTrustedContextFactsAndIncompleteHistoryWithoutCurrentRoute() {
        ResourceScope scope = new ResourceScope("org", "team", "test");
        TimeRange range = new TimeRange(1000, 2000);
        var context = new InvestigationContext("task", "incident", scope, "gateway", "namespace", ResourceTarget.route("/orders"), range, Map.of(), "RBAC");
        var event = new ChangeEvent("event", scope, "gateway", "release:ci", "ci-1", "RELEASE_SUCCEEDED", 1500L, 1800,
                new TimeRange(1500, 1500), new ChangeEvent.Subject("orders", "/orders", "uid", 1, List.of("orders"), "v2", null, null, null),
                Map.of("releaseId", "deployment-1", "artifact", "build-123"), List.of("时钟未校准"));
        AtomicInteger authorization = new AtomicInteger();
        CapabilityExecutor executor = new CapabilityExecutor(null, null, null, null, null, null, null, null,
                CapabilityRegistry.standard(), id -> authorization.incrementAndGet(), id -> context, query -> {
                    assertEquals(scope, query.context().scope()); assertEquals(new TimeRange(0, 2000), query.range());
                    assertEquals(range, query.context().requestedRange());
                    return new TimelineSnapshot(query.range(), 2000, List.of(event), List.of(), false, List.of("不是完整变更历史"));
                });
        var result = executor.execute(AgentCapability.CHANGE_TIMELINE_QUERY, ResourceTarget.route("/orders"), "/orders", "task", null, range);
        assertEquals(1, authorization.get()); assertEquals(1, result.evidence().size());
        var evidence = result.evidence().get(0);
        assertEquals(EvidenceQuality.PARTIAL, evidence.envelope().qualityStatus());
        assertEquals(range, evidence.envelope().requestedRange()); assertNull(evidence.envelope().coverageRange());
        assertEquals(scope, evidence.envelope().context().scope());
        assertTrue(evidence.modelText().contains("build-123")); assertTrue(evidence.modelText().contains("源时间"));
        assertEquals(false, evidence.envelope().facts().get("causalityConfirmed"));
        assertEquals(1, ((List<?>) evidence.envelope().facts().get("events")).size());
    }

    @Test void releaseFactsRemainVisibleToModelWhenThereAreManyBaselineEvents() {
        ResourceScope scope = new ResourceScope("org", "team", "test");
        TimeRange range = new TimeRange(1000, 2000);
        var context = new InvestigationContext("task", null, scope, "gateway", "namespace", ResourceTarget.service("orders"), range, Map.of(), "RBAC");
        var subject = new ChangeEvent.Subject(null, null, null, 0, List.of("orders"), null, null, null, null);
        List<ChangeEvent> events = new ArrayList<>();
        for (int i = 0; i < 30; i++) { events.add(new ChangeEvent("baseline-" + i, scope, "gateway", "rover.config.gateway", "baseline-" + i,
                "CONFIG_BASELINE", null, 1000 + i, new TimeRange(1000 + i, 1000 + i), subject, Map.of("baseline", i), List.of())); }
        events.add(new ChangeEvent("release", scope, "gateway", "release:ci", "ci", "RELEASE_SUCCEEDED", 1500L, 1800,
                new TimeRange(1500, 1500), subject, Map.of("releaseId", "deployment-1", "artifact", "important-build"), List.of()));
        CapabilityExecutor executor = new CapabilityExecutor(null, null, null, null, null, null, null, null, CapabilityRegistry.standard(),
                id -> { }, id -> context, query -> new TimelineSnapshot(query.range(), 2000, events, List.of(), false, List.of()));
        var evidence = executor.execute(AgentCapability.CHANGE_TIMELINE_QUERY, ResourceTarget.service("orders"), "", "task", null, range).evidence().get(0);
        assertTrue(evidence.modelText().contains("important-build"));
        assertEquals(31, ((List<?>) evidence.envelope().facts().get("events")).size());
    }
}
