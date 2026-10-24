package com.rover.gateway.core.route;

import com.rover.common.json.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Durable instance isolation overlay for one Gateway process. Expiry always requires review. */
public final class RouteInstanceIsolationStore {
    private final String url;
    private final AtomicReference<Map<String, Isolation>> active = new AtomicReference<>(Map.of());
    private volatile boolean readable = true;
    private final Object writeLock = new Object();

    public RouteInstanceIsolationStore(Path routeOverlay) {
        Path database = routeOverlay.toAbsolutePath().resolveSibling(routeOverlay.getFileName() + ".isolations");
        this.url = "jdbc:h2:file:" + database.toAbsolutePath().toString().replace('\\', '/')
                + ";DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=5000";
        initialize();
        refresh();
    }

    public Isolation isolate(Key key, Source source, String actor, String reason, String capacityEvidence,
                                          int minimumHealthyAfter, long expiresAt,
                                          long expectedVersion, String operationId, long now) {
        return isolate(key, source, actor, reason, capacityEvidence, minimumHealthyAfter, expiresAt, expectedVersion, operationId, now, () -> { });
    }

    public synchronized Isolation isolate(Key key, Source source, String actor, String reason, String capacityEvidence,
                                          int minimumHealthyAfter, long expiresAt, long expectedVersion,
                                          String operationId, long now, Runnable validateCurrentState) {
        Objects.requireNonNull(validateCurrentState, "validateCurrentState");
        synchronized (writeLock) {
        validateActor(actor); validateOperation(operationId);
        if (source == null || reason == null || reason.isBlank() || reason.length() > 1000
                || capacityEvidence == null || capacityEvidence.isBlank() || capacityEvidence.length() > 256
                || minimumHealthyAfter < 1 || minimumHealthyAfter > 10_000
                || expiresAt <= now || expiresAt - now > 7L * 24 * 60 * 60 * 1000 || expectedVersion < 0) {
            throw new IllegalArgumentException("隔离来源、原因、有效期或版本无效");
        }
        String requestHash = hash("ISOLATE|" + key.canonical() + "|" + source + "|" + actor + "|" + reason + "|" + capacityEvidence + "|" + minimumHealthyAfter + "|" + expiresAt + "|" + expectedVersion);
        try (Connection c = open()) {
            c.setAutoCommit(false);
            Isolation replay = replay(c, operationId, requestHash);
            if (replay != null) { c.commit(); refresh(); return replay; }
            Isolation current = find(c, key);
            long version = current == null ? 0 : current.version();
            if (version != expectedVersion) { throw new IllegalStateException("隔离记录版本冲突，请刷新后重试"); }
            if (current != null && current.active() && current.source() == Source.MANUAL && source == Source.AGENT) {
                throw new IllegalStateException("Agent 不得覆盖人工隔离");
            }
            validateCurrentState.run();
            if (current == null) {
                try (PreparedStatement count = c.prepareStatement("SELECT COUNT(*) FROM route_instance_isolation"); ResultSet rs = count.executeQuery()) {
                    if (rs.next() && rs.getLong(1) >= 1000) { throw new IllegalStateException("隔离记录已达 1000 条上限，请先清理已恢复历史"); }
                }
            }
            Isolation saved = new Isolation(key, source, State.ISOLATED, actor, reason, capacityEvidence, minimumHealthyAfter,
                    current == null ? now : current.createdAtMillis(), expiresAt, Math.addExact(version, 1));
            try (PreparedStatement p = c.prepareStatement("MERGE INTO route_instance_isolation (route_uid,generation,route_id,service_name,service_group,instance_id,host,port,source,state,requested_by,reason,capacity_evidence,minimum_healthy_after,created_at,expires_at,updated_at,version) KEY(route_uid,generation,route_id,service_name,service_group,instance_id,host,port) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                bind(p, saved); p.executeUpdate();
            }
            recordOperation(c, operationId, requestHash, saved); c.commit(); refresh(); return saved;
        } catch (Exception ex) { throw failure("保存实例隔离失败", ex); }
        }
    }

    public Isolation restore(Key key, Source source, String actor, long expectedVersion,
                                          String capacityEvidence, int minimumHealthyAfter,
                                          String operationId, long now) {
        return restore(key, source, actor, expectedVersion, capacityEvidence, minimumHealthyAfter, operationId, now, () -> { });
    }

    public Isolation restore(Key key, Source source, String actor, long expectedVersion,
                                          String capacityEvidence, int minimumHealthyAfter,
                                          String operationId, long now, Runnable validateCurrentState) {
        Objects.requireNonNull(validateCurrentState, "validateCurrentState");
        synchronized (writeLock) {
        validateActor(actor); validateOperation(operationId);
        if (source == null || expectedVersion < 1 || capacityEvidence == null || capacityEvidence.isBlank()
                || capacityEvidence.length() > 256 || minimumHealthyAfter < 1 || minimumHealthyAfter > 10_000) {
            throw new IllegalArgumentException("恢复来源、版本或容量证据无效");
        }
        String requestHash = restoreHash(key, source, actor, expectedVersion, capacityEvidence, minimumHealthyAfter);
        try (Connection c = open()) {
            c.setAutoCommit(false);
            Isolation replay = replay(c, operationId, requestHash);
            if (replay != null) { c.commit(); refresh(); return replay; }
            Isolation current = find(c, key);
            if (current == null || !current.active() || current.version() != expectedVersion) {
                throw new IllegalStateException("隔离记录已恢复、已变化或版本过期");
            }
            if (current.source() != source) {
                throw new IllegalStateException(current.source() == Source.MANUAL
                        ? "Agent 不得解除人工隔离" : "恢复来源与隔离来源不匹配");
            }
            validateCurrentState.run();
            Isolation saved = new Isolation(key, current.source(), State.RESTORED, actor, current.reason(),
                    capacityEvidence, minimumHealthyAfter,
                    current.createdAtMillis(), current.expiresAtMillis(), Math.addExact(current.version(), 1));
            try (PreparedStatement p = c.prepareStatement("UPDATE route_instance_isolation SET state=?,requested_by=?,capacity_evidence=?,minimum_healthy_after=?,updated_at=?,version=? WHERE route_uid=? AND generation=? AND route_id=? AND service_name=? AND service_group=? AND instance_id=? AND host=? AND port=? AND version=?")) {
                p.setString(1, saved.state().name()); p.setString(2, saved.requestedBy()); p.setString(3, saved.capacityEvidence());
                p.setInt(4, saved.minimumHealthyAfter()); p.setLong(5, now); p.setLong(6, saved.version());
                bindKey(p, 7, key); p.setString(13, key.host()); p.setInt(14, key.port()); p.setLong(15, expectedVersion);
                if (p.executeUpdate() != 1) { throw new IllegalStateException("隔离记录并发变化，请刷新后重试"); }
            }
            recordOperation(c, operationId, requestHash, saved); c.commit(); refresh(); return saved;
        } catch (Exception ex) { throw failure("恢复实例流量失败", ex); }
        }
    }

    public List<Isolation> list() {
        try (Connection c = open(); PreparedStatement p = c.prepareStatement("SELECT route_uid,generation,route_id,service_name,service_group,instance_id,host,port,source,state,requested_by,reason,capacity_evidence,minimum_healthy_after,created_at,expires_at,version FROM route_instance_isolation ORDER BY updated_at DESC"); ResultSet rs = p.executeQuery()) {
            List<Isolation> rows = new ArrayList<>(); while (rs.next()) { rows.add(read(rs)); } return List.copyOf(rows);
        } catch (Exception ex) { throw failure("读取实例隔离状态失败", ex); }
    }

    /** Return a prior result only when this exact operation ID still represents the same intent. */
    public synchronized Isolation replayIsolate(Key key, Source source, String actor, String reason, String capacityEvidence,
                                                int minimumHealthyAfter, long expiresAt, long expectedVersion, String operationId) {
        validateActor(actor); validateOperation(operationId);
        return replayOperation(operationId, hash("ISOLATE|" + key.canonical() + "|" + source + "|" + actor + "|"
                + reason + "|" + capacityEvidence + "|" + minimumHealthyAfter + "|" + expiresAt + "|" + expectedVersion));
    }

    public synchronized Isolation replayRestore(Key key, Source source, String actor, long expectedVersion, String capacityEvidence,
                                               int minimumHealthyAfter, String operationId) {
        validateActor(actor); validateOperation(operationId);
        return replayOperation(operationId, restoreHash(key, source, actor, expectedVersion, capacityEvidence, minimumHealthyAfter));
    }

    private String restoreHash(Key key, Source source, String actor, long expectedVersion, String capacityEvidence, int minimumHealthyAfter) {
        return hash("RESTORE|" + key.canonical() + "|" + source + "|" + actor + "|" + expectedVersion
                + "|" + capacityEvidence + "|" + minimumHealthyAfter);
    }

    private Isolation replayOperation(String operationId, String requestHash) {
        synchronized (writeLock) {
            try (Connection c = open()) {
                Isolation replayed = replay(c, operationId, requestHash);
                if (replayed != null) { refresh(); }
                return replayed;
            }
            catch (Exception ex) { throw failure("查询实例隔离操作回执失败", ex); }
        }
    }

    public List<Isolation> active(String routeUid, long generation, String routeId, String serviceName, String group) {
        String normalizedGroup = group == null ? "" : group;
        return active.get().values().stream().filter(row -> row.key().routeUid().equals(routeUid)
                && row.key().generation() == generation && row.key().routeId().equals(routeId)
                && row.key().serviceName().equals(serviceName)
                && (normalizedGroup.isEmpty() || row.key().group().equals(normalizedGroup))).toList();
    }

    /** Exact identity lookup for the live request snapshot, including the host/port fence. */
    public boolean isolates(Key key) {
        Isolation row = active.get().get(key.cacheKey());
        return !readable || row != null;
    }

    public boolean healthy() {
        if (!readable) { return false; }
        try (Connection c = open(); PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM route_instance_isolation"); ResultSet rs = p.executeQuery()) { return rs.next(); }
        catch (Exception ex) { return false; }
    }

    /** Runtime snapshot health; request routing must fail closed while the durable overlay is unreadable. */
    public boolean isReadable() { return readable; }

    private void refresh() {
        try (Connection c = open(); PreparedStatement p = c.prepareStatement("SELECT route_uid,generation,route_id,service_name,service_group,instance_id,host,port,source,state,requested_by,reason,capacity_evidence,minimum_healthy_after,created_at,expires_at,version FROM route_instance_isolation WHERE state='ISOLATED'"); ResultSet rs = p.executeQuery()) {
            Map<String, Isolation> snapshot = new java.util.HashMap<>();
            while (rs.next()) { Isolation row = read(rs); snapshot.put(row.key().cacheKey(), row); }
            active.set(Map.copyOf(snapshot)); readable = true;
        } catch (Exception ex) { readable = false; throw failure("刷新实例隔离覆盖失败", ex); }
    }

    public int pruneRestoredBefore(long cutoffMillis) {
        try (Connection c = open(); PreparedStatement p = c.prepareStatement("DELETE FROM route_instance_isolation WHERE state='RESTORED' AND updated_at<?")) {
            p.setLong(1, cutoffMillis); return p.executeUpdate();
        } catch (Exception ex) { throw failure("清理历史隔离记录失败", ex); }
    }

    private Isolation find(Connection c, Key key) throws Exception {
        try (PreparedStatement p = c.prepareStatement("SELECT route_uid,generation,route_id,service_name,service_group,instance_id,host,port,source,state,requested_by,reason,capacity_evidence,minimum_healthy_after,created_at,expires_at,version FROM route_instance_isolation WHERE route_uid=? AND generation=? AND route_id=? AND service_name=? AND service_group=? AND instance_id=? AND host=? AND port=?")) {
            bindKey(p, 1, key); p.setString(7, key.host()); p.setInt(8, key.port()); try (ResultSet rs = p.executeQuery()) { return rs.next() ? read(rs) : null; }
        }
    }

    private Isolation replay(Connection c, String operationId, String requestHash) throws Exception {
        try (PreparedStatement p = c.prepareStatement("SELECT request_hash,result_json FROM route_instance_isolation_operation WHERE operation_id=?")) {
            p.setString(1, operationId); try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) { return null; }
                if (!requestHash.equals(rs.getString(1))) { throw new IllegalStateException("相同操作号不能用于不同的隔离意图"); }
                return fromMap(JsonCodec.parseObjectMap(rs.getString(2)));
            }
        }
    }

    private void recordOperation(Connection c, String id, String hash, Isolation row) throws Exception {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO route_instance_isolation_operation(operation_id,request_hash,result_json,at_millis) VALUES(?,?,?,?)")) {
            p.setString(1, id); p.setString(2, hash); p.setString(3, JsonCodec.toJson(toMap(row))); p.setLong(4, System.currentTimeMillis()); p.executeUpdate();
        }
    }

    private void initialize() {
        try (Connection c = open(); var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS route_instance_isolation(route_uid VARCHAR(128) NOT NULL,generation BIGINT NOT NULL,route_id VARCHAR(256) NOT NULL,service_name VARCHAR(256) NOT NULL,service_group VARCHAR(256) NOT NULL,instance_id VARCHAR(512) NOT NULL,host VARCHAR(255) NOT NULL,port INT NOT NULL,source VARCHAR(16) NOT NULL,state VARCHAR(32) NOT NULL,requested_by VARCHAR(128) NOT NULL,reason VARCHAR(1000) NOT NULL,capacity_evidence VARCHAR(256) NOT NULL,minimum_healthy_after INT NOT NULL,created_at BIGINT NOT NULL,expires_at BIGINT NOT NULL,updated_at BIGINT NOT NULL,version BIGINT NOT NULL,PRIMARY KEY(route_uid,generation,route_id,service_name,service_group,instance_id,host,port))");
            s.execute("CREATE TABLE IF NOT EXISTS route_instance_isolation_operation(operation_id VARCHAR(128) PRIMARY KEY,request_hash VARCHAR(64) NOT NULL,result_json CLOB NOT NULL,at_millis BIGINT NOT NULL)");
            String primaryKey = null;
            try (var p = c.prepareStatement("SELECT constraint_name FROM information_schema.table_constraints WHERE table_name='ROUTE_INSTANCE_ISOLATION' AND constraint_type='PRIMARY KEY'" ); ResultSet rs = p.executeQuery()) { if (rs.next()) { primaryKey = rs.getString(1); } }
            if (primaryKey != null) {
                boolean hasAddressFence = false;
                try (var p = c.prepareStatement("SELECT COUNT(*) FROM information_schema.key_column_usage WHERE table_name='ROUTE_INSTANCE_ISOLATION' AND constraint_name=? AND column_name IN ('HOST','PORT')")) {
                    p.setString(1, primaryKey); try (ResultSet rs = p.executeQuery()) { hasAddressFence = rs.next() && rs.getInt(1) == 2; }
                }
                if (!hasAddressFence) {
                    s.execute("ALTER TABLE route_instance_isolation DROP CONSTRAINT " + primaryKey);
                    s.execute("ALTER TABLE route_instance_isolation ADD PRIMARY KEY(route_uid,generation,route_id,service_name,service_group,instance_id,host,port)");
                }
            }
        } catch (Exception ex) { throw failure("初始化 Gateway 实例隔离存储失败", ex); }
    }

    private Connection open() throws Exception { return DriverManager.getConnection(url, "sa", ""); }
    private static void bind(PreparedStatement p, Isolation v) throws Exception {
        Key k = v.key(); bindKey(p, 1, k); p.setString(7, k.host()); p.setInt(8, k.port()); p.setString(9, v.source().name());
        p.setString(10, v.state().name()); p.setString(11, v.requestedBy()); p.setString(12, v.reason());
        p.setString(13, v.capacityEvidence()); p.setInt(14, v.minimumHealthyAfter()); p.setLong(15, v.createdAtMillis());
        p.setLong(16, v.expiresAtMillis()); p.setLong(17, System.currentTimeMillis()); p.setLong(18, v.version());
    }
    private static void bindKey(PreparedStatement p, int start, Key k) throws Exception {
        p.setString(start, k.routeUid()); p.setLong(start + 1, k.generation()); p.setString(start + 2, k.routeId());
        p.setString(start + 3, k.serviceName()); p.setString(start + 4, k.group()); p.setString(start + 5, k.instanceId());
    }
    private static Isolation read(ResultSet r) throws Exception {
        return new Isolation(new Key(r.getString(1), r.getLong(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getInt(8)),
                Source.valueOf(r.getString(9)), State.valueOf(r.getString(10)), r.getString(11), r.getString(12), r.getString(13),
                r.getInt(14), r.getLong(15), r.getLong(16), r.getLong(17));
    }
    private static Map<String, Object> toMap(Isolation v) {
        Key k = v.key();
        return Map.ofEntries(Map.entry("routeUid", k.routeUid()), Map.entry("generation", k.generation()), Map.entry("routeId", k.routeId()),
                Map.entry("serviceName", k.serviceName()), Map.entry("group", k.group()), Map.entry("instanceId", k.instanceId()), Map.entry("host", k.host()), Map.entry("port", k.port()),
                Map.entry("source", v.source().name()), Map.entry("state", v.state().name()), Map.entry("requestedBy", v.requestedBy()), Map.entry("reason", v.reason()),
                Map.entry("capacityEvidence", v.capacityEvidence()), Map.entry("minimumHealthyAfter", v.minimumHealthyAfter()),
                Map.entry("createdAtMillis", v.createdAtMillis()), Map.entry("expiresAtMillis", v.expiresAtMillis()), Map.entry("version", v.version()));
    }
    private static Isolation fromMap(Map<String, Object> m) {
        return new Isolation(new Key(s(m,"routeUid"), n(m,"generation"), s(m,"routeId"), s(m,"serviceName"), s(m,"group"), s(m,"instanceId"), s(m,"host"), (int)n(m,"port")),
                Source.valueOf(s(m,"source")), State.valueOf(s(m,"state")), s(m,"requestedBy"), s(m,"reason"), s(m,"capacityEvidence"),
                (int)n(m,"minimumHealthyAfter"), n(m,"createdAtMillis"), n(m,"expiresAtMillis"), n(m,"version"));
    }
    private static String s(Map<String,Object> m, String k) { return Objects.toString(m.get(k), ""); }
    private static long n(Map<String,Object> m, String k) { return ((Number)m.get(k)).longValue(); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("JVM 不支持 SHA-256", ex); }
    }
    private static void validateActor(String actor) { if (actor == null || actor.isBlank() || actor.length() > 128) { throw new IllegalArgumentException("操作者无效"); } }
    private static void validateOperation(String id) { if (id == null || !id.matches("[A-Za-z0-9._:-]{1,128}")) { throw new IllegalArgumentException("operationId 无效"); } }
    private static RuntimeException failure(String message, Exception ex) { return ex instanceof RuntimeException r ? r : new IllegalStateException(message, ex); }

    public record Key(String routeUid, long generation, String routeId, String serviceName, String group,
                      String instanceId, String host, int port) {
        public Key {
            if (blank(routeUid) || generation <= 0 || blank(routeId) || blank(serviceName) || blank(instanceId)
                    || blank(host) || port < 1 || port > 65535) { throw new IllegalArgumentException("路由/实例身份不完整"); }
            group = group == null ? "" : group;
        }
        private static boolean blank(String value) { return value == null || value.isBlank(); }
        public String cacheKey() { return canonical(); }
        String canonical() {
            return java.util.stream.Stream.of(routeUid, Long.toString(generation), routeId, serviceName, group, instanceId, host, Integer.toString(port))
                    .map(value -> value.length() + ":" + value).collect(java.util.stream.Collectors.joining());
        }
    }
    public record Isolation(Key key, Source source, State state, String requestedBy, String reason,
                            String capacityEvidence, int minimumHealthyAfter,
                            long createdAtMillis, long expiresAtMillis, long version) {
        public boolean active() { return state == State.ISOLATED; }
        public String lifecycle(long now) { return active() && now >= expiresAtMillis ? "EXPIRED_PENDING_REVIEW" : state.name(); }
    }
    public enum Source { MANUAL, AGENT }
    public enum State { ISOLATED, RESTORED }
}
