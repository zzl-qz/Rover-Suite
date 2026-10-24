package com.rover.gateway.core.route;

import com.rover.common.json.JsonCodec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 本机单 Gateway 的事务存储：路由和回执同一事务，不能用它充当跨节点共享库。 */
public class RouteOperationStore {
    public static final long RECEIPT_RETENTION_MILLIS = Duration.ofDays(30).toMillis();
    private final Path database;
    private final Path fence;
    private final Clock clock;

    public record Receipt(String operationId, String requestHash, String status, int revision,
                          String message, long atMillis, long expiresAtMillis, List<RouteConfig> routes) {
        public boolean expired(long now) { return now >= expiresAtMillis; }
    }

    public RouteOperationStore(Path overlay) { this(overlay, Clock.systemUTC()); }

    public RouteOperationStore(Path overlay, Clock clock) {
        this.database = overlay.toAbsolutePath().resolveSibling(overlay.getFileName() + ".state");
        this.fence = database.resolveSibling(database.getFileName() + ".fence.json");
        this.clock = clock;
    }

    public long now() { return clock.millis(); }
    public Path databaseFile() { return Path.of(database + ".mv.db"); }
    public Path fenceFile() { return fence; }
    public boolean exists() { return Files.exists(databaseFile()) || Files.exists(fence); }

    /** 已有存储不可读或恢复了旧 DB 时拒绝启动，绝不退回 YAML 覆盖已确认事实。 */
    public synchronized RouteOverlayStore.AppliedState load() {
        if (!exists()) { return null; }
        try (Connection connection = open()) {
            return verifiedState(connection);
        } catch (SQLException | IOException ex) {
            throw failure(ex);
        }
    }

    public synchronized Receipt find(String operationId) {
        if (!exists()) { return null; }
        try (Connection connection = open()) {
            verifiedState(connection);
            return find(connection, operationId);
        } catch (SQLException | IOException ex) {
            throw failure(ex);
        }
    }

    /** commit 前写入高水位见证；中断导致 DB 落后时停写，不能猜测提交结果。 */
    public synchronized void commit(RouteOverlayStore.AppliedState before,
                                    RouteOverlayStore.AppliedState after, Receipt receipt) {
        try {
            Files.createDirectories(database.getParent());
            boolean fresh = !exists();
            try (Connection connection = open()) {
                connection.setAutoCommit(false);
                if (fresh) { initialize(connection, before); }
                RouteOverlayStore.AppliedState actual = verifiedState(connection);
                if (actual.revision() != before.revision()
                        || !actual.appliedOperationId().equals(before.appliedOperationId())
                        || !RouteOverlayStore.toRows(actual.routes()).equals(RouteOverlayStore.toRows(before.routes()))) {
                    throw new IllegalStateException("持久路由与运行态不一致，已停止写入；请重启并对账");
                }
                Receipt previous = find(connection, receipt.operationId());
                if (previous != null) {
                    throw new IllegalStateException("操作号已存在，禁止覆盖持久回执: " + receipt.operationId());
                }
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO route_operation VALUES(?,?,?,?,?,?,?,?)")) {
                    insert.setString(1, receipt.operationId());
                    insert.setString(2, receipt.requestHash());
                    insert.setString(3, receipt.status());
                    insert.setInt(4, receipt.revision());
                    insert.setString(5, receipt.message());
                    insert.setLong(6, receipt.atMillis());
                    insert.setLong(7, receipt.expiresAtMillis());
                    insert.setString(8, JsonCodec.toJson(RouteOverlayStore.toRows(receipt.routes())));
                    insert.executeUpdate();
                }
                try (PreparedStatement update = connection.prepareStatement("UPDATE route_state SET revision=?, state_json=? WHERE id=1 AND revision=?")) {
                    update.setInt(1, after.revision());
                    update.setString(2, stateJson(after));
                    update.setInt(3, before.revision());
                    if (update.executeUpdate() != 1) { throw new IllegalStateException("持久路由版本冲突"); }
                }
                writeFence(storeId(connection), after.revision(), operationCount(connection));
                commitTransaction(connection);
            }
        } catch (SQLException | IOException ex) {
            throw failure(ex);
        }
    }

    /** 首次启用管理面即保存资源身份，正常重启不改变 UID。 */
    public synchronized void initialize(RouteOverlayStore.AppliedState initial) {
        if (exists()) { load(); return; }
        try {
            Files.createDirectories(database.getParent());
            try (Connection connection = open()) {
                connection.setAutoCommit(false);
                initialize(connection, initial);
            }
        } catch (SQLException | IOException ex) { throw failure(ex); }
    }

    private void initialize(Connection connection, RouteOverlayStore.AppliedState before) throws SQLException, IOException {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE route_state (id INT PRIMARY KEY CHECK(id=1), "
                    + "store_id VARCHAR(36) NOT NULL, revision INT NOT NULL, state_json CLOB NOT NULL)");
            statement.execute("CREATE TABLE route_operation (operation_id VARCHAR(200) PRIMARY KEY, "
                    + "request_hash VARCHAR(64) NOT NULL, status VARCHAR(20) NOT NULL, revision INT NOT NULL, "
                    + "message CLOB NOT NULL, at_millis BIGINT NOT NULL, expires_at BIGINT NOT NULL, routes_json CLOB NOT NULL)");
        }
        String storeId = UUID.randomUUID().toString();
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO route_state VALUES(1,?,?,?)")) {
            insert.setString(1, storeId);
            insert.setInt(2, before.revision());
            insert.setString(3, stateJson(before));
            insert.executeUpdate();
        }
        // 旧 overlay 只知道最新操作号，保留 UNKNOWN 墓碑，不能伪造 APPLIED 回执或允许复用。
        if (!before.appliedOperationId().isBlank()) {
            try (PreparedStatement legacy = connection.prepareStatement("INSERT INTO route_operation VALUES(?,?,?,?,?,?,?,?)")) {
                legacy.setString(1, before.appliedOperationId());
                legacy.setString(2, "");
                legacy.setString(3, "UNKNOWN");
                legacy.setInt(4, before.revision());
                legacy.setString(5, "旧 overlay 不含完整回执，需人工对账");
                legacy.setLong(6, before.appliedAtMillis());
                legacy.setLong(7, Long.MAX_VALUE);
                legacy.setString(8, "[]");
                legacy.executeUpdate();
            }
        }
        writeFence(storeId, before.revision(), operationCount(connection));
        connection.commit();
    }

    /** 独立提交点，便于用真实子进程验证提交前后崩溃的恢复契约。 */
    protected void commitTransaction(Connection connection) throws SQLException {
        connection.commit();
    }

    private Connection open() throws SQLException {
        // WRITE_DELAY=0：事务提交等待日志刷盘；H2 自身持有文件锁，拒绝其他进程写同一数据库。
        return DriverManager.getConnection("jdbc:h2:file:" + database.toString().replace('\\', '/')
                + ";WRITE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
    }

    private RouteOverlayStore.AppliedState verifiedState(Connection connection) throws SQLException, IOException {
        if (!Files.exists(fence)) { throw new IOException("缺少路由存储见证文件，禁止自动重建"); }
        Map<String, Object> marker = JsonCodec.parseObjectMap(Files.readString(fence));
        try (var statement = connection.createStatement(); ResultSet row = statement.executeQuery("SELECT * FROM route_state WHERE id=1")) {
            if (!row.next() || !row.getString("store_id").equals(marker.get("storeId"))
                    || row.getInt("revision") != ((Number) marker.get("revision")).intValue()
                    || operationCount(connection) != ((Number) marker.get("operationCount")).longValue()) {
                throw new IOException("路由存储代次/高水位不一致，可能提交中断或恢复旧备份；需人工对账");
            }
            RouteOverlayStore.AppliedState state = JsonCodec.fromJson(row.getString("state_json"), RouteOverlayStore.AppliedState.class);
            boolean upgraded = false;
            for (RouteConfig route : state.routes()) {
                if (route.getResourceUid() == null || route.getResourceUid().isBlank() || route.getGeneration() <= 0) {
                    if (route.getResourceUid() == null || route.getResourceUid().isBlank()) {
                    route.setResourceUid(UUID.randomUUID().toString());
                    }
                    route.setGeneration(1);
                    upgraded = true;
                }
            }
            if (upgraded) {
                try (PreparedStatement update = connection.prepareStatement("UPDATE route_state SET state_json=? WHERE id=1")) {
                    update.setString(1, stateJson(state));
                    update.executeUpdate();
                }
                // 仅升级内部身份，不增加路由 revision、不修改历史回执。
            }
            return state;
        }
    }

    private String storeId(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); ResultSet row = statement.executeQuery("SELECT store_id FROM route_state WHERE id=1")) {
            if (!row.next()) { throw new SQLException("缺少路由状态"); }
            return row.getString(1);
        }
    }

    private long operationCount(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM route_operation")) {
            row.next();
            return row.getLong(1);
        }
    }

    private Receipt find(Connection connection, String operationId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT * FROM route_operation WHERE operation_id=?")) {
            query.setString(1, operationId);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) { return null; }
                return new Receipt(operationId, row.getString("request_hash"), row.getString("status"),
                        row.getInt("revision"), row.getString("message"), row.getLong("at_millis"),
                        row.getLong("expires_at"), routes(row.getString("routes_json")));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<RouteConfig> routes(String json) {
        return RouteOverlayStore.toRoutes(JsonCodec.fromJson(json, List.class));
    }

    private static String stateJson(RouteOverlayStore.AppliedState state) { return JsonCodec.toJson(state); }

    private void writeFence(String storeId, int revision, long operationCount) throws IOException {
        Path temporary = fence.resolveSibling(fence.getFileName() + ".tmp");
        byte[] bytes = JsonCodec.toJson(Map.of("storeId", storeId, "revision", revision,
                "operationCount", operationCount)).getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) { channel.write(buffer); }
            channel.force(true);
        }
        Files.move(temporary, fence, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private IllegalStateException failure(Exception ex) {
        return new IllegalStateException("路由事务存储不可用，未确认的操作必须按原操作号对账: " + database, ex);
    }
}
