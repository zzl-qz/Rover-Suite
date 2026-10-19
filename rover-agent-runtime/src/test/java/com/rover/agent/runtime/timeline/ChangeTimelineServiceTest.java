package com.rover.agent.runtime.timeline;

import com.rover.agent.core.model.*;
import com.rover.agent.core.port.*;
import com.rover.agent.core.timeline.*;
import com.rover.agent.core.timeline.ChangeEvent.Subject;
import com.rover.agent.runtime.repository.AgentStore;
import com.rover.agent.runtime.timeline.JdbcChangeTimeline.Observation;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ChangeTimelineServiceTest {
    @TempDir Path directory;
    private static final long NOW = 1_792_400_000_000L;
    private final MutableClock clock = new MutableClock(NOW);
    private final Control control = new Control();
    private ChangeTimelineService service(AgentStore store) { return new ChangeTimelineService(store, control, 5000, clock); }
    private AgentStore store(String name) { return AgentStore.file(directory.resolve(name).toString()); }
    private TimelineQuery query(ResourceTarget target, long from, long to, int limit) {
        return new TimelineQuery(new InvestigationContext("task", "incident", control.resourceScope(), control.controlTargetId(),
                control.resourceNamespace(), target, new TimeRange(from, to), Map.of(), "LIVE_RBAC_V1"), target, new TimeRange(from, to), limit);
    }
    private static ChangeTimelineService.Release release(String event, String service, long at, String artifact) {
        return new ChangeTimelineService.Release(event, "deploy-" + event, artifact, service, "v2", null, null, null, null, "SUCCEEDED", at);
    }
    private static Observation route(String prefix, String service, String uid, int weight) {
        return new Observation(new Subject(prefix, prefix, uid, 1, List.of(service), null, null, null, null),
                Map.of("services", List.of(service), "weight", weight));
    }
    private static Observation instance(boolean healthy) {
        return new Observation(new Subject(null, null, null, 0, List.of("orders"), "v2", "instance-1", "127.0.0.1:8000", null), Map.of("healthy", healthy));
    }

    @Test void releaseReceiptAndFactsSurviveRestartAndConflictingReplayDoesNotOverwrite() throws Exception {
        String id;
        try (AgentStore store = store("release")) {
            var timeline = service(store);
            var request = release("event-1", "orders", NOW - 1000, "sha256:artifact-a");
            var original = timeline.ingestRelease("connector:ci", request);
            id = original.event().eventId();
            clock.at += 1000;
            var replay = timeline.ingestRelease("connector:ci", request);
            assertTrue(replay.duplicate()); assertEquals(original.event(), replay.event());
            assertThrows(JdbcChangeTimeline.Conflict.class, () -> timeline.ingestRelease("connector:ci", release("event-1", "orders", NOW - 1000, "changed")));
            assertEquals(1, store.durable().audit(control.resourceNamespace(), 0, 100).stream().filter(a -> a.kind().equals("RELEASE_INGESTED")).count());
        }
        try (AgentStore store = store("release")) {
            var timeline = service(store);
            var result = timeline.query(query(ResourceTarget.service("orders"), NOW - 5000, NOW + 5000, 100));
            assertEquals(id, result.events().get(0).eventId());
            assertEquals("sha256:artifact-a", result.events().get(0).facts().get("artifact"));
            assertEquals(EvidenceQuality.PARTIAL, result.quality());
            assertTrue(timeline.ingestRelease("connector:ci", release("event-1", "orders", NOW - 1000, "sha256:artifact-a")).duplicate());
        }
    }

    @Test void historicalRouteMappingHonorsLongestPrefixAndDoesNotBorrowCurrentService() throws Exception {
        try (AgentStore store = store("mapping")) {
            var timeline = service(store);
            timeline.observe("rover.routes", Map.of("root", route("/", "fallback", "root-uid", 100), "order", route("/orders", "old-orders", "old-uid", 10)));
            clock.at += 1000;
            timeline.ingestRelease("connector:ci", release("old", "old-orders", clock.at, "old-build"));
            timeline.ingestRelease("connector:ci", release("unrelated", "fallback", clock.at, "fallback-build"));
            long oldWindow = clock.at;
            clock.at += 500;
            timeline.observe("rover.routes", Map.of("root", route("/", "fallback", "root-uid", 100), "order", route("/orders", "old-orders", "old-uid", 10)));
            clock.at -= 500;
            clock.at += 1000;
            timeline.observe("rover.routes", Map.of("root", route("/", "fallback", "root-uid", 100), "order", route("/orders", "new-orders", "new-uid", 50)));
            clock.at += 1000;
            timeline.ingestRelease("connector:ci", release("new", "new-orders", oldWindow, "new-build"));
            var past = timeline.query(query(ResourceTarget.route("/orders/checkout"), oldWindow, oldWindow, 100));
            var artifacts = past.events().stream().map(e -> e.facts().get("artifact")).filter(Objects::nonNull).toList();
            assertTrue(artifacts.contains("old-build")); assertFalse(artifacts.contains("fallback-build"));
            assertFalse(artifacts.contains("new-build"), "不能用当前服务关联回填过去的发布关系");
            var beforeBaseline = timeline.query(query(ResourceTarget.route("/orders/checkout"), NOW - 10000, NOW - 5000, 100));
            assertTrue(beforeBaseline.events().isEmpty());
            assertTrue(beforeBaseline.limitations().stream().anyMatch(s -> s.contains("未借用当前路由")));
            var oldOnly = timeline.query(query(ResourceTarget.route("/orders/checkout"), NOW, NOW + 500, 100));
            assertFalse(oldOnly.events().stream().anyMatch(e -> "new-build".equals(e.facts().get("artifact"))));
        }
    }

    @Test void failedReadsDoNotRemoveInstancesAndRestartRetainsBaselineAndGap() throws Exception {
        try (AgentStore store = store("poll")) {
            var timeline = service(store);
            timeline.unavailable("rover.instances");
            timeline.observe("rover.instances", Map.of("i", instance(true)));
            clock.at += 1000;
            timeline.unavailable("rover.instances");
            assertEquals(List.of("INSTANCE_BASELINE"), timeline.query(query(ResourceTarget.service("orders"), NOW, clock.at, 100)).events().stream().map(ChangeEvent::kind).toList());
            clock.at += 1000;
            timeline.observe("rover.instances", Map.of("i", instance(false)));
            var changed = timeline.query(query(ResourceTarget.service("orders"), NOW, clock.at, 100)).events().stream().filter(e -> e.kind().equals("INSTANCE_CHANGED")).findFirst().orElseThrow();
            assertNull(changed.occurredAtMillis()); assertEquals(new TimeRange(NOW, clock.at), changed.occurrenceRange());
            assertEquals(true, changed.facts().get("observationGap"));
        }
        try (AgentStore store = store("poll")) {
            clock.at += 1000;
            var restarted = service(store);
            restarted.observe("rover.instances", Map.of("i", instance(false)));
            assertEquals(2, restarted.query(query(ResourceTarget.service("orders"), NOW, clock.at, 100)).events().size());
            clock.at += 1000;
            restarted.observe("rover.instances", Map.of());
            assertEquals("INSTANCE_REMOVED", restarted.query(query(ResourceTarget.service("orders"), clock.at, clock.at, 100)).events().get(0).kind());
        }
    }

    @Test void numericRoundTripDoesNotInventChangesAndFactsAreDeepImmutable() throws Exception {
        try (AgentStore store = store("numbers")) {
            var timeline = service(store);
            var mutable = new HashMap<String, Object>(); mutable.put("services", new ArrayList<>(List.of("orders"))); mutable.put("weight", 10);
            var observation = new Observation(route("/orders", "orders", "uid", 10).subject(), mutable);
            timeline.observe("rover.routes", Map.of("r", observation));
            mutable.put("weight", 50); clock.at += 1000;
            var identical = route("/orders", "orders", "uid", 10);
            timeline.observe("rover.routes", Map.of("r", new Observation(identical.subject(), identical.values(), "revision-2")));
            var rows = timeline.query(query(ResourceTarget.route("/orders"), NOW, clock.at, 100)).events();
            assertEquals(1, rows.size());
            assertThrows(UnsupportedOperationException.class, () -> rows.get(0).facts().put("after", null));
            assertEquals(new java.math.BigDecimal("1E+1"), ((Map<?, ?>) rows.get(0).facts().get("after")).get("weight"));
        }
    }

    @Test void scopeMismatchMissingHistoryClockDifferencesOrderingAndTruncationAreExplicit() throws Exception {
        try (AgentStore store = store("boundaries")) {
            var timeline = service(store);
            timeline.ingestRelease("connector:ci", release("later", "orders", NOW, "later"));
            timeline.ingestRelease("connector:ci", release("earlier", "orders", NOW - 600_000, "earlier"));
            var rows = timeline.query(query(ResourceTarget.service("orders"), NOW - 700_000, NOW + 1000, 100));
            assertEquals(List.of("earlier", "later"), rows.events().stream().map(e -> e.facts().get("artifact")).toList());
            assertTrue(rows.events().get(0).limitations().stream().anyMatch(s -> s.contains("五分钟")));
            assertTrue(rows.events().get(0).limitations().stream().anyMatch(s -> s.contains("乱序")));
            assertTrue(timeline.query(query(ResourceTarget.service("orders"), NOW - 700_000, NOW + 1000, 1)).truncated());
            timeline.ingestRelease("connector:ci", release("future-clock", "orders", NOW + 600_000, "future-clock-build"));
            var received = timeline.query(query(ResourceTarget.service("orders"), NOW, NOW + 1000, 100));
            assertTrue(received.events().stream().anyMatch(e -> "future-clock-build".equals(e.facts().get("artifact"))
                    && e.limitations().stream().anyMatch(s -> s.contains("按接收时间命中"))));
            var other = new InvestigationContext("task", null, new ResourceScope("org", "team", "other"), "gateway", "ns", null, null, Map.of(), "RBAC");
            assertThrows(AgentAccess.Denied.class, () -> timeline.query(new TimelineQuery(other, ResourceTarget.service("orders"), new TimeRange(NOW, NOW + 1), 10)));
            assertThrows(IllegalArgumentException.class, () -> timeline.ingestRelease("connector:ci", release("bad", "orders", Long.MAX_VALUE, "bad")));
        }
    }

    @Test void storageFailureRollsBackReleaseAuditAndObservationBaselineTogether() throws Exception {
        String path = directory.resolve("atomic").toString();
        try (AgentStore store = AgentStore.file(path);
             var connection = java.sql.DriverManager.getConnection("jdbc:h2:file:" + path + ";DB_CLOSE_DELAY=0;AUTO_SERVER=TRUE;WRITE_DELAY=0", "sa", "");
             var statement = connection.createStatement()) {
            var timeline = service(store);
            statement.execute("ALTER TABLE agent_audit ADD CONSTRAINT reject_release CHECK(event_kind <> 'RELEASE_INGESTED')");
            var request = release("retry", "orders", NOW, "build");
            assertThrows(IllegalStateException.class, () -> timeline.ingestRelease("connector:ci", request));
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM agent_change_event")) { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
            statement.execute("ALTER TABLE agent_audit DROP CONSTRAINT reject_release");
            assertFalse(timeline.ingestRelease("connector:ci", request).duplicate());
            timeline.observe("rover.routes", Map.of("r", route("/orders", "orders", "uid", 10)));
            clock.at += 1000;
            statement.execute("ALTER TABLE agent_change_event ADD CONSTRAINT reject_change CHECK(event_kind <> 'ROUTE_CHANGED')");
            assertThrows(IllegalStateException.class, () -> timeline.observe("rover.routes", Map.of("r", route("/orders", "orders", "uid", 50))));
            assertEquals(NOW, store.durable().timeline().baseline(control.resourceNamespace(), "rover.routes").observedAt());
            statement.execute("ALTER TABLE agent_change_event DROP CONSTRAINT reject_change");
            timeline.observe("rover.routes", Map.of("r", route("/orders", "orders", "uid", 50)));
            assertEquals(1, timeline.query(query(ResourceTarget.route("/orders"), NOW, clock.at, 100)).events().stream().filter(e -> e.kind().equals("ROUTE_CHANGED")).count());
        }
    }

    private static final class MutableClock extends Clock {
        long at; MutableClock(long at) { this.at = at; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(at); }
    }
    private static final class Control implements RouteControlPort {
        public ResourceScope resourceScope() { return new ResourceScope("org", "team", "test"); }
        public String resourceNamespace() { return "timeline-test"; }
        public String controlTargetId() { return "gateway"; }
        public RouteControlState state() { throw new AssertionError("Timeline must not use current routing for historical association"); }
        public RouteChangePreview preview(String r,String s,String g,int w) { throw new AssertionError(); }
        public RouteChangeResult adjustTargetWeight(String r,String s,String g,int w,int v,String o) { throw new AssertionError(); }
        public RouteOperation operation(String id) { throw new AssertionError(); }
    }
}
