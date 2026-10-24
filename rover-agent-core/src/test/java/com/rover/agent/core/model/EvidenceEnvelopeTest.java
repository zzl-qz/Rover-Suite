package com.rover.agent.core.model;

import com.rover.agent.core.capability.*;
import com.rover.agent.core.snapshot.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceEnvelopeTest {
    @Test void legacyNeverInventsCoverageOrIdentity() {
        var evidence = Evidence.of("task", EvidenceType.TRACE, "gateway", "trace", "HTTP 503", "/traces", 100);
        assertEquals(EvidenceQuality.PARTIAL, evidence.envelope().qualityStatus());
        assertNull(evidence.envelope().coverageRange()); assertTrue(evidence.envelope().resourceRefs().isEmpty());
        assertEquals(evidence.envelope(), evidence.withToolCallId("call-1").envelope());
    }

    @Test void deepFreezeAndDigestSurviveMapOrderingAndNumericJsonTypes() {
        var row = new LinkedHashMap<String, Object>(); row.put("count", 1L); row.put("origin", "UPSTREAM");
        var rows = new ArrayList<>(List.of(row));
        var envelope = envelope(Map.of("rows", rows), null);
        row.put("origin", "GATEWAY"); rows.clear();
        var reloaded = envelope(Map.of("rows", List.of(Map.of("origin", "UPSTREAM", "count", 1))), envelope.payloadDigest());
        assertEquals(envelope, reloaded);
        assertThrows(UnsupportedOperationException.class, () -> envelope.facts().put("invented", true));
        assertThrows(IllegalArgumentException.class, () -> envelope(Map.of("count", 2), envelope.payloadDigest()));
    }

    @Test void requestedHistoryDoesNotUpgradeCurrentStateOrLoseFacts() {
        var evidence = Evidence.of("task", EvidenceType.CONFIG, "gateway", "config", "now", "/configs", 100);
        var original = evidence.envelope();
        var descriptor = CapabilityRegistry.standard().descriptor(AgentCapability.CONFIG_READ).orElseThrow();
        var result = CapabilityResult.success(AgentCapability.CONFIG_READ, descriptor, List.of(evidence), List.of())
                .inTimeRange(new TimeRange(1, 10));
        assertEquals(EvidenceQuality.OUT_OF_RANGE, result.evidence().get(0).envelope().qualityStatus());
        assertEquals(original.payloadDigest(), result.evidence().get(0).envelope().payloadDigest());
        assertEquals(EvidenceQuality.PARTIAL, original.qualityStatus());
    }

    @Test void traceEvidenceUsesCapturedUidAndTrustedScopeRatherThanCurrentRoute() {
        long now = System.currentTimeMillis();
        var scope = new ResourceScope("org", "team", "prod");
        var captured = new TraceObservation("orders", "old-route", 2, "svc", "v2", "host:80", "UPSTREAM", "NONE", null, true, List.of());
        var context = new InvestigationContext("task", "incident", scope, "gateway-a", "namespace", null, null,
                Map.of("modelCalls", "1"), "LIVE_RBAC_V1");
        var executor = new CapabilityExecutor(null, null, null,
                path -> new TraceSnapshot(true, 1, List.of(new TraceRow("trace", path, 503, now - 100, captured)),
                        now, now - 1000, 0, "RANDOM_PLUS_SLOW"), null, null, null, null,
                CapabilityRegistry.standard(), id -> { }, id -> context);
        var current = new RouteSnapshot("orders", "/api/orders", "svc", "v1,v2", "", "", now, "new-route", 1);
        var result = executor.execute(AgentCapability.TRACE_QUERY, ResourceTarget.route("/api/orders"), "/api/orders",
                "task", current, new TimeRange(now - 1000, now));
        var evidence = result.evidence().get(0);
        assertEquals(List.of(new ResourceRef(scope, "gateway-a", "GATEWAY_ROUTE", "orders", "old-route", 2)), evidence.envelope().resourceRefs());
        assertEquals("incident", evidence.envelope().context().incidentId());
        assertEquals(EvidenceQuality.PARTIAL, evidence.envelope().qualityStatus());
        assertTrue(evidence.summary().contains("responseOrigin=UPSTREAM"));
        assertTrue(evidence.summary().contains("group=v2"));
    }

    private static EvidenceEnvelope envelope(Map<String, Object> facts, String digest) {
        return new EvidenceEnvelope(1, "gateway", "METRIC", "query", "v1", null, null, null, 100, null,
                "ALL", 1, EvidenceQuality.AVAILABLE, null, List.of(), facts, List.of(), null, digest, "v1");
    }

    @Test void disabledCollectorDoesNotEraseRetainedHistoricalFacts() {
        long now = System.currentTimeMillis();
        var executor = new CapabilityExecutor(null, null, null,
                path -> new TraceSnapshot(false, 1, List.of(new TraceRow("old", path, 503, now - 100)),
                        now, now - 1000, 0, "RANDOM_PLUS_SLOW"), null, null, null, null, CapabilityRegistry.standard());
        var result = executor.execute(AgentCapability.TRACE_QUERY, ResourceTarget.route("/p"), "/p", "task",
                null, new TimeRange(now - 1000, now));
        assertEquals(EvidenceQuality.PARTIAL, result.evidence().get(0).envelope().qualityStatus());
        assertEquals(1, result.evidence().get(0).envelope().sampleCount());
        assertTrue(result.evidence().get(0).summary().contains("追踪已关闭"));
    }
}
