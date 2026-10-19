package com.rover.agent.core.investigation;

import static org.junit.jupiter.api.Assertions.*;
import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.model.*;
import com.rover.agent.core.snapshot.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HypothesisAssessmentTest {
    private static final String PATH = "/orders";
    private final RouteSnapshot route = new RouteSnapshot("r1", PATH, "orders", "", "", "", 10);

    @Test void conclusionWithoutEvidenceReferenceBecomesUnknown() {
        var result = HypothesisAssessment.evaluate(input(null, true, null, null), List.of());
        assertEquals(Verdict.UNKNOWN, find(result, "H1").status());
        assertFalse(find(result, "H1").missingEvidence().isEmpty());
        assertEquals(Confidence.LOW, result.confidence());
    }

    @Test void supportAndCounterEvidenceUseActualIdsRatherThanSourceNames() {
        Evidence routes = evidence(EvidenceType.ROUTE, "路由匹配", "/api/routes");
        Evidence instances = evidence(EvidenceType.INSTANCE, "实例健康", "/api/instances");
        var result = HypothesisAssessment.evaluate(input(route, true, List.of(), null), List.of(routes, instances));
        assertEquals(List.of(routes.evidenceId()), find(result, "H1").counterEvidenceIds());
        assertEquals(List.of(instances.evidenceId()), find(result, "H3").supportingEvidenceIds());
        assertEquals(Verdict.CONFIRMED, find(result, "H3").status());
        assertTrue(find(result, "H3").nextCapabilities().isEmpty());
    }

    @Test void emptySampleIsUnknownWithExplicitGapAndAlternativeQuery() {
        var trace = new TraceSnapshot(true, .01, List.of(), System.currentTimeMillis());
        var result = HypothesisAssessment.evaluate(input(route, true,
                List.of(new InstanceSnapshot("orders", "", "i1", "host", 80, true, 100, true, 0)), trace),
                List.of(evidence(EvidenceType.ROUTE, "路由匹配", "/api/routes"),
                        evidence(EvidenceType.INSTANCE, "实例健康", "/api/instances"),
                        evidence(EvidenceType.TRACE, "路径追踪记录", "/api/traces?path=/orders")));
        var h = find(result, "H5");
        assertEquals(Verdict.UNKNOWN, h.status());
        assertTrue(h.counterEvidenceIds().isEmpty());
        assertFalse(h.missingEvidence().isEmpty());
        assertTrue(h.nextCapabilities().contains(AgentCapability.LOG_QUERY));
    }

    @Test void historicalCurrentContextAndDifferentTargetCannotSupplyVerdictReferences() {
        Evidence current = evidence(EvidenceType.ROUTE, "路由匹配", "/api/routes");
        current = current.withEnvelope(current.envelope().requested(new TimeRange(10, 20), true));
        var result = HypothesisAssessment.evaluate(new FindingsInput(PATH, null, true, DiscoveryMode.NAMESERVER,
                null, null, null, new TimeRange(10, 20)), List.of(current));
        assertEquals(Verdict.UNKNOWN, find(result, "H1").status());
        assertTrue(find(result, "H1").supportingEvidenceIds().isEmpty());
        Evidence wrong = evidence(EvidenceType.ROUTE, "路由匹配", "/api/routes");
        wrong = wrong.withEnvelope(wrong.envelope().inContext(InvestigationContext.legacy("t").query(ResourceTarget.route("/other"), null), List.of()));
        assertEquals(Verdict.UNKNOWN, find(HypothesisAssessment.evaluate(input(null, true, null, null), List.of(wrong)), "H1").status());
    }

    @Test void recentReleaseProvidesALeadAndNeverProvesCausality() {
        Evidence event = evidence(EvidenceType.EVENT, "发布与变更时间线", "/api/agent/timeline");
        event = event.withEnvelope(new EvidenceEnvelope(1, "rover.change-timeline", "EVENT", "query", "v1",
                null, null, null, 10, null, "PARTIAL", 1, EvidenceQuality.PARTIAL, null, List.of(),
                Map.of("events", List.of(Map.of("kind", "RELEASE_SUCCEEDED"))), List.of(), null, null, "v1"));
        var h = find(HypothesisAssessment.evaluate(input(null, false, null, null), List.of(event)), "H7");
        assertEquals(Verdict.UNKNOWN, h.status());
        assertEquals(List.of(event.evidenceId()), h.supportingEvidenceIds());
        assertFalse(h.missingEvidence().isEmpty());
    }

    private FindingsInput input(RouteSnapshot r, boolean read, List<InstanceSnapshot> instances, TraceSnapshot traces) {
        return new FindingsInput(PATH, r, read, DiscoveryMode.NAMESERVER, instances, traces, null);
    }
    private static Evidence evidence(EvidenceType type, String title, String ref) {
        return Evidence.of("t", type, "test", title, "test", ref, 10);
    }
    private static Hypothesis find(Findings result, String id) {
        return result.hypotheses().stream().filter(h -> id.equals(h.id())).findFirst().orElseThrow();
    }
}
