package com.rover.agent.runtime.repository;

import com.rover.agent.core.model.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JdbcEvidenceEnvelopeTest {
    @TempDir Path directory;

    @Test void nativeFactsContextAndDigestSurviveRestartAndOldPayloadStillLoads() throws Exception {
        String path = directory.resolve("evidence").toString();
        var scope = new ResourceScope("org", "team", "prod");
        var context = new InvestigationContext("t", null, scope, "gateway-a", "namespace", ResourceTarget.route("/p"),
                new TimeRange(100, 200), Map.of("modelCalls", "1"), "LIVE_RBAC_V1");
        var envelope = new EvidenceEnvelope(1, "gateway.final-responses", "METRIC", "/metrics", "v1",
                new TimeRange(100, 200), new TimeRange(110, 190), new TimeRange(110, 190), 201, null,
                "ALL_FINAL_REQUESTS", 120, EvidenceQuality.PARTIAL, context,
                List.of(new ResourceRef(scope, "gateway-a", "GATEWAY_ROUTE", "orders", "old", 2)),
                Map.of("requests", 120L, "dimensions", List.of(Map.of("group", "v2", "5xx", 58L))),
                List.of("boundary"), null, null, "v1");
        var evidence = Evidence.of("t", EvidenceType.METRIC, "gateway", "window", "v2 returned 503", "/metrics", 201)
                .withEnvelope(envelope);
        try (var store = new JdbcAgentStore(path)) {
            store.sessions().save(new Session("s", "alice", "test", null, SessionStatus.ACTIVE, 1, 2, List.of()));
            store.tasks().save(new TaskView("t", "s", null, TaskStatus.COMPLETED, null, "/p", ResourceTarget.route("/p"),
                    "why", 1, 2, List.of(), null, null, null, TaskType.INVESTIGATION, null, List.of(), List.of(), List.of(evidence)));
        }
        try (var store = new JdbcAgentStore(path)) {
            var saved = store.tasks().find("t").orElseThrow();
            var loaded = saved.evidence().get(0);
            assertEquals(evidence, loaded);
            assertEquals(envelope.payloadDigest(), loaded.envelope().payloadDigest());
            var changed = new Evidence(loaded.evidenceId(), loaded.taskId(), loaded.type(), loaded.source(), loaded.title(),
                    "rewritten historical fact", loaded.rawReference(), loaded.metadata(), loaded.observedAtMillis(), loaded.envelope());
            assertThrows(IllegalArgumentException.class, () -> store.tasks().save(new TaskView(saved.taskId(), saved.sessionId(),
                    null, saved.status(), null, saved.path(), saved.target(), saved.question(), 1, 2, List.of(), null, null,
                    null, TaskType.INVESTIGATION, null, List.of(), List.of(), List.of(changed))));
            assertEquals(loaded, store.tasks().find("t").orElseThrow().evidence().get(0), "拒绝改写时事务不能破坏原证据");
        }
        // 升级前同一列仅存 metadata，不能补造历史覆盖或身份。
        try (var connection = DriverManager.getConnection("jdbc:h2:file:" + path + ";AUTO_SERVER=TRUE", "sa", "");
             var statement = connection.prepareStatement("UPDATE agent_evidence SET payload_json=? WHERE evidence_id=?")) {
            statement.setString(1, "{\"sampleSize\":\"120\",\"windowSeconds\":\"60\"}");
            statement.setString(2, evidence.evidenceId()); statement.executeUpdate();
        }
        try (var store = new JdbcAgentStore(path)) {
            var legacy = store.tasks().find("t").orElseThrow().evidence().get(0);
            assertEquals(EvidenceQuality.PARTIAL, legacy.envelope().qualityStatus());
            assertNull(legacy.envelope().coverageRange()); assertNull(legacy.envelope().context());
            assertTrue(legacy.envelope().resourceRefs().isEmpty()); assertEquals(120, legacy.envelope().sampleCount());
        }
    }
}
