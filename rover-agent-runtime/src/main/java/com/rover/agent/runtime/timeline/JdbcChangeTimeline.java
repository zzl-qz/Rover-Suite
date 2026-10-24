package com.rover.agent.runtime.timeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rover.agent.core.timeline.ChangeEvent;
import com.rover.agent.core.timeline.ChangeEvent.Subject;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.TimeRange;
import java.sql.*;
import java.util.*;

/** Uses the Agent connection/monitor: events and observation baselines commit together. */
public final class JdbcChangeTimeline {
    private static final ObjectMapper JSON = com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .disable(com.fasterxml.jackson.databind.MapperFeature.AUTO_DETECT_IS_GETTERS).build();
    private final Connection connection;
    private final Object monitor;
    public JdbcChangeTimeline(Connection connection, Object monitor) { this.connection = connection; this.monitor = monitor; }

    public record Observation(Subject subject, Map<String, Object> values, String sourceRevision) {
        public Observation(Subject subject, Map<String, Object> values) { this(subject, values, null); }
        public Observation {
            if (subject == null) { throw new IllegalArgumentException("缺少观测资源身份"); }
            values = new com.rover.agent.core.model.EvidenceEnvelope(1, "timeline", "EVENT", "", "timeline-v1", null,
                    null, null, 1, null, "POLL", 1, null, null, List.of(), values, List.of(), null, null, "allowlist-v1").facts();
        }
    }
    public record Baseline(long firstObservedAt, long observedAt, long lastAttemptAt, String runId,
                           boolean available, long gapCount, Map<String, Observation> resources) { }
    public record AppendResult(ChangeEvent event, boolean duplicate) { }

    public Baseline baseline(String namespace, String source) {
        synchronized (monitor) {
            return select("SELECT payload_json FROM agent_timeline_source WHERE resource_namespace=? AND source_id=?",
                    Baseline.class, namespace, source).stream().findFirst().orElse(null);
        }
    }

    public List<Map<String, Object>> sources(String namespace) {
        synchronized (monitor) {
            try (var ps = connection.prepareStatement("SELECT source_id,payload_json FROM agent_timeline_source WHERE resource_namespace=? ORDER BY source_id")) {
                ps.setString(1, namespace);
                try (var rs = ps.executeQuery()) {
                    List<Map<String, Object>> result = new ArrayList<>();
                    while (rs.next()) {
                        Baseline b = decode(rs.getString(2), Baseline.class);
                        result.add(Map.of("sourceId", rs.getString(1), "firstObservedAtMillis", b.firstObservedAt(),
                                "lastSuccessAtMillis", b.observedAt(), "lastAttemptAtMillis", b.lastAttemptAt(),
                                "available", b.available(), "gapCount", b.gapCount()));
                    }
                    return List.copyOf(result);
                }
            } catch (SQLException e) { throw failure(e); }
        }
    }

    public void observation(String namespace, String source, Baseline baseline, List<ChangeEvent> events) {
        synchronized (monitor) {
            transaction(() -> {
                for (ChangeEvent event : events) { insert(namespace, event, event.eventId()); }
                update("MERGE INTO agent_timeline_source(resource_namespace,source_id,payload_json) KEY(resource_namespace,source_id) VALUES(?,?,?)",
                        namespace, source, encode(baseline));
                return null;
            });
        }
    }

    public AppendResult append(String namespace, ChangeEvent event, String intentDigest) {
        synchronized (monitor) {
            return transaction(() -> {
                try (var ps = connection.prepareStatement("SELECT payload_json,intent_digest FROM agent_change_event WHERE resource_namespace=? AND source_id=? AND source_event_id=?")) {
                    ps.setString(1, namespace); ps.setString(2, event.sourceId()); ps.setString(3, event.sourceEventId());
                    try (var rs = ps.executeQuery()) {
                        if (rs.next()) {
                            if (!intentDigest.equals(rs.getString(2))) { throw new Conflict("同一发布事件号携带了不同内容"); }
                            return new AppendResult(decode(rs.getString(1), ChangeEvent.class), true);
                        }
                    }
                } catch (SQLException e) { throw failure(e); }
                ChangeEvent storedEvent = event;
                try (var ps = connection.prepareStatement("SELECT MAX(range_from) FROM agent_change_event WHERE resource_namespace=? AND source_id=?")) {
                    ps.setString(1, namespace); ps.setString(2, event.sourceId());
                    try (var rs = ps.executeQuery()) {
                        if (rs.next() && rs.getLong(1) > event.occurrenceRange().fromMillis()) {
                            List<String> limits = new ArrayList<>(event.limitations()); limits.add("同一来源已有更晚的源时间：本事件乱序或迟到到达，不能以接收顺序代表发生顺序。");
                            storedEvent = new ChangeEvent(event.eventId(), event.scope(), event.controlTargetId(), event.sourceId(), event.sourceEventId(),
                                    event.kind(), event.occurredAtMillis(), event.observedAtMillis(), event.occurrenceRange(), event.subject(), event.facts(), limits);
                        }
                    }
                } catch (SQLException e) { throw failure(e); }
                insert(namespace, storedEvent, intentDigest);
                update("INSERT INTO agent_audit(event_kind,aggregate_id,actor,resource_namespace,payload_json,created_at) VALUES(?,?,?,?,?,?)",
                        "RELEASE_INGESTED", storedEvent.eventId(), storedEvent.sourceId(), namespace, encode(storedEvent.json()), storedEvent.observedAtMillis());
                return new AppendResult(storedEvent, false);
            });
        }
    }

    /** Historical route observations only. Current configuration is never used to assign past service events. */
    public List<ChangeEvent> routeHistory(String namespace, String path, long toMillis) {
        synchronized (monitor) {
            return select("SELECT e.payload_json FROM agent_change_event e WHERE e.resource_namespace=? AND e.source_id='rover.routes' "
                            + "AND e.range_from<=? AND (e.route_id=? OR e.route_prefix=? OR (e.route_prefix='/' AND LEFT(?,1)='/') OR LEFT(?,LENGTH(e.route_prefix)+1)=CONCAT(e.route_prefix,'/')) "
                            + "ORDER BY e.range_to DESC,e.event_id DESC LIMIT 1001", ChangeEvent.class, namespace, toMillis, path, path, path, path);
        }
    }

    public List<ChangeEvent> query(String namespace, ResourceTarget target, TimeRange range, Set<String> services, int limit) {
        synchronized (monitor) {
            String predicate;
            List<Object> args = new ArrayList<>(List.of(namespace, range.fromMillis(), range.toMillis(), range.fromMillis(), range.toMillis()));
            switch (target.type()) {
                case ROUTE -> {
                    predicate = "(e.route_id=? OR e.route_prefix=? OR (e.route_prefix='/' AND LEFT(?,1)='/') OR (e.route_prefix<>'' AND LEFT(?,LENGTH(e.route_prefix)+1)=CONCAT(e.route_prefix,'/')))";
                    args.add(target.value()); args.add(target.value()); args.add(target.value()); args.add(target.value());
                }
                case SERVICE -> { predicate = link("SERVICE"); args.add(target.value()); }
                case INSTANCE -> { predicate = "(" + link("INSTANCE") + " OR " + link("HOST") + ")"; args.add(target.value()); args.add(target.value()); }
                default -> throw new IllegalArgumentException("时间线查询必须指定路由、服务或实例");
            }
            for (String service : services) { predicate += " OR " + link("SERVICE"); args.add(service); }
            // Global component config is relevant context, explicitly marked global in its subject.
            predicate += " OR (e.event_kind LIKE 'CONFIG_%' AND e.component<>'')";
            args.add(limit);
            return select("SELECT e.payload_json FROM agent_change_event e WHERE e.resource_namespace=? AND ((e.range_to>=? AND e.range_from<=?) "
                    + "OR (e.source_id LIKE 'release:%' AND e.observed_at>=? AND e.observed_at<=?)) "
                    + "AND (" + predicate + ") ORDER BY e.range_from DESC,e.observed_at DESC,e.event_id DESC LIMIT ?",
                    ChangeEvent.class, args.toArray());
        }
    }

    private static String link(String kind) {
        return "EXISTS(SELECT 1 FROM agent_change_link l WHERE l.resource_namespace=e.resource_namespace AND l.event_id=e.event_id AND l.link_kind='" + kind + "' AND l.resource_id=?)";
    }

    private void insert(String namespace, ChangeEvent event, String intentDigest) {
        Subject s = event.subject();
        update("INSERT INTO agent_change_event(resource_namespace,event_id,source_id,source_event_id,event_kind,range_from,range_to,observed_at,route_id,route_prefix,component,intent_digest,payload_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                namespace, event.eventId(), event.sourceId(), event.sourceEventId(), event.kind(), event.occurrenceRange().fromMillis(),
                event.occurrenceRange().toMillis(), event.observedAtMillis(), text(s.routeId()), text(s.businessPrefix()), text(s.component()), intentDigest, encode(event));
        for (String service : s.services()) { link(namespace, event.eventId(), "SERVICE", service); }
        link(namespace, event.eventId(), "INSTANCE", s.instanceId()); link(namespace, event.eventId(), "HOST", s.hostPort());
    }
    private void link(String namespace, String eventId, String kind, String value) {
        if (value != null && !value.isBlank()) { update("INSERT INTO agent_change_link VALUES(?,?,?,?)", namespace, eventId, kind, value); }
    }
    private static String text(String value) { return value == null ? "" : value; }
    private <T> List<T> select(String sql, Class<T> type, Object... args) {
        try (var ps = connection.prepareStatement(sql)) {
            bind(ps, args);
            try (var rs = ps.executeQuery()) {
                List<T> result = new ArrayList<>();
                while (rs.next()) { result.add(decode(rs.getString(1), type)); }
                return List.copyOf(result);
            }
        } catch (SQLException e) { throw failure(e); }
    }
    private void update(String sql, Object... args) {
        try (var ps = connection.prepareStatement(sql)) { bind(ps, args); ps.executeUpdate(); }
        catch (SQLException e) { throw failure(e); }
    }
    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) { ps.setObject(i + 1, args[i]); }
    }
    private <T> T transaction(java.util.function.Supplier<T> work) {
        try {
            connection.setAutoCommit(false);
            try { T result = work.get(); connection.commit(); return result; }
            catch (RuntimeException e) { connection.rollback(); throw e; }
            finally { connection.setAutoCommit(true); }
        } catch (SQLException e) { throw failure(e); }
    }
    private static String encode(Object value) {
        try { return JSON.writeValueAsString(value); } catch (Exception e) { throw failure(e); }
    }
    private static <T> T decode(String json, Class<T> type) {
        try { return JSON.readValue(json, type); } catch (Exception e) { throw failure(e); }
    }
    private static IllegalStateException failure(Exception e) { return new IllegalStateException("持久变更时间线操作失败", e); }
    public static final class Conflict extends RuntimeException { public Conflict(String message) { super(message); } }
}
