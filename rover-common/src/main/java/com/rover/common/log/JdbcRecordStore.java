package com.rover.common.log;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

/**
 * JDBC 追加式记录库，支持异步写入、时间范围查询与超期清理，兼容 H2 和 MySQL。
 * 高优记录入队短暂等待，遥测队列满时丢弃并计数；单写线程批量落盘。
 * 查询与清理使用独立连接。
 */
@Slf4j
public class JdbcRecordStore implements RecordStore {

    private static final int BATCH = 200;
    /** 高优证据入队阻塞等待上限：正常情况下高优队列几乎为空，立即成功；仅在极端积压时短暂等待，不无限阻塞业务 */
    private static final long CRITICAL_OFFER_TIMEOUT_MS = 500;

    private final String jdbcUrl;
    /** 高优队列：诊断证据，尽量不丢 */
    private final BlockingQueue<Record> criticalQueue;
    /** 普通队列：遥测，满了 best-effort 丢 */
    private final BlockingQueue<Record> normalQueue;
    private final AtomicLong droppedNormal = new AtomicLong(0);
    private final AtomicLong droppedCritical = new AtomicLong(0);
    private final Thread writer;
    private volatile boolean running = true;
    private final Connection writeConn;

    public JdbcRecordStore(String dbPath) {
        this(dbPath, 8192);
    }

    public JdbcRecordStore(String dbPath, int normalCapacity) {
        this(dbPath, normalCapacity, Math.max(normalCapacity * 2, 16384));
    }

    public JdbcRecordStore(String dbPath, int normalCapacity, int criticalCapacity) {
        if (dbPath == null || dbPath.isBlank()) {
            throw new IllegalArgumentException("dbPath 不能为空");
        }
        File file = new File(dbPath);
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        // AUTO_SERVER=TRUE：允许 IDEA 等外部进程在应用运行期间以同一 URL 并发连接查看数据
        this.jdbcUrl = "jdbc:h2:file:" + dbPath + ";DB_CLOSE_DELAY=0;AUTO_SERVER=TRUE";
        log.info("落盘记录库已就绪: 库文件={}.mv.db (相对 JVM 工作目录解析)", file.getAbsolutePath());
        this.normalQueue = new ArrayBlockingQueue<>(Math.max(1, normalCapacity));
        this.criticalQueue = new ArrayBlockingQueue<>(Math.max(1, criticalCapacity));
        this.writeConn = openConnection();
        migrate(writeConn);
        this.writer = new Thread(this::writeLoop, "rover-record-writer");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    private Connection openConnection() {
        try {
            // 显式 sa/空密码建库与连接：H2 2.x 无凭据建库时用户是空串而非 sa，会导致控制台用 sa 登录报 28000
            return DriverManager.getConnection(jdbcUrl, "sa", "");
        } catch (SQLException e) {
            throw new IllegalStateException("打开 H2 日志库失败: " + jdbcUrl, e);
        }
    }

    /**
     * 通过 schema_migrations 记录版本，按序执行尚未应用的迁移。
     * 仅支持前向迁移，撤销变更需新增版本。
     */
    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "baseline",
                    "CREATE TABLE IF NOT EXISTS records ("
                            + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                            + "ts BIGINT NOT NULL, "
                            + "type VARCHAR(40) NOT NULL, "
                            + "target VARCHAR(255), "
                            + "payload CLOB);"
                            + "CREATE INDEX IF NOT EXISTS idx_records_ts ON records(ts);"
                            + "CREATE INDEX IF NOT EXISTS idx_records_target_ts ON records(target, ts)"));
    // 将来改表结构时，在此追加，例如：
    // new Migration(2, "add level column", "ALTER TABLE records ADD COLUMN level VARCHAR(16)")

    private record Migration(int version, String description, String sql) {}

    private void migrate(Connection conn) {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_migrations ("
                    + "version INT PRIMARY KEY, "
                    + "description VARCHAR(255), "
                    + "applied_at TIMESTAMP)");
        } catch (SQLException e) {
            throw new IllegalStateException("初始化迁移版本表失败", e);
        }
        int current = currentVersion(conn);
        for (Migration m : MIGRATIONS) {
            if (m.version() <= current) {
                continue;                       // 已应用，跳过
            }
            applyMigration(conn, m);            // 向前迁移 + 记版本
            log.info("落盘记录库迁移已应用: v{} {}", m.version(), m.description());
        }
    }

    private int currentVersion(Connection conn) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(version) FROM schema_migrations")) {
            if (rs.next()) {
                return rs.getInt(1);           // 无记录时 JDBC 返回 0
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取迁移版本失败", e);
        }
        return 0;
    }

    private void applyMigration(Connection conn, Migration m) {
        try {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                for (String sql : m.sql().split(";")) {     // 一条迁移可含多条语句
                    if (!sql.trim().isEmpty()) {
                        st.execute(sql.trim());
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO schema_migrations (version, description, applied_at) VALUES (?, ?, ?)")) {
                ps.setInt(1, m.version());
                ps.setString(2, m.description());
                ps.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
                ps.executeUpdate();
            }
            conn.commit();
            conn.setAutoCommit(true);
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException ignored) {
                // 回滚失败忽略
            }
            try {
                conn.setAutoCommit(true);
            } catch (SQLException ignored) {
                // 恢复自动提交失败忽略
            }
            throw new IllegalStateException("应用迁移 v" + m.version() + " 失败: " + m.description(), e);
        }
    }

    @Override
    public void log(Record record) {
        if (record == null) {
            return;
        }
        if (record.type().isCritical()) {
            // 诊断证据：尽量不丢。队列满则短暂阻塞等待，仍失败才计数丢弃（极端积压才发生）
            try {
                if (!criticalQueue.offer(record, CRITICAL_OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    long d = droppedCritical.incrementAndGet();
                    if (d % 100 == 1) {
                        log.error("关键日志队列持续积压, 累计丢弃 {} 条诊断证据! 检查磁盘/写入性能", d);
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                if (!criticalQueue.offer(record)) {
                    droppedCritical.incrementAndGet();
                    log.error("关键日志入队被中断, 丢弃诊断证据: {}", record.type());
                }
            }
        } else {
            // 遥测：满则直接丢弃, best-effort, 绝不让业务等待
            if (!normalQueue.offer(record)) {
                long d = droppedNormal.incrementAndGet();
                if (d % 1000 == 1) {
                    log.warn("遥测日志队列已满, 累计丢弃 {} 条(best-effort, 不阻塞业务)", d);
                }
            }
        }
    }

    private void writeLoop() {
        List<Record> batch = new ArrayList<>(BATCH);
        while (running) {
            Record head;
            try {
                // 高优优先：等 200ms 看有无关键记录
                head = criticalQueue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (head != null) {
                batch.add(head);
                criticalQueue.drainTo(batch, BATCH - 1);
            } else {
                // 高优空，转消费普通队列
                Record n = normalQueue.poll();
                if (n == null) {
                    continue;
                }
                batch.add(n);
                normalQueue.drainTo(batch, BATCH - 1);
            }
            flushBatch(batch);
            batch.clear();
        }
        // 关闭前把残余队列刷完（高优优先）
        criticalQueue.drainTo(batch);
        normalQueue.drainTo(batch);
        if (!batch.isEmpty()) {
            flushBatch(batch);
        }
    }

    private void flushBatch(List<Record> batch) {
        try (PreparedStatement ps = writeConn.prepareStatement(
                "INSERT INTO records (ts, type, target, payload) VALUES (?, ?, ?, ?)")) {
            writeConn.setAutoCommit(false);
            for (Record r : batch) {
                ps.setLong(1, r.ts());
                ps.setString(2, r.type().name());
                ps.setString(3, r.target());
                ps.setString(4, r.payload());
                ps.addBatch();
            }
            ps.executeBatch();
            writeConn.commit();
            writeConn.setAutoCommit(true);
        } catch (SQLException e) {
            log.warn("批量写入日志失败, 丢弃 {} 条", batch.size(), e);
            try {
                writeConn.rollback();
                writeConn.setAutoCommit(true);
            } catch (SQLException ignored) {
                // 回滚也失败则放弃这批
            }
        }
    }

    @Override
    public List<Record> query(LogQuery q) {
        List<Record> result = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ts, type, target, payload FROM records WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (q.from() != null) {
            sql.append(" AND ts >= ?");
            params.add(q.from());
        }
        if (q.to() != null) {
            sql.append(" AND ts <= ?");
            params.add(q.to());
        }
        if (q.target() != null && !q.target().isBlank()) {
            sql.append(" AND target = ?");
            params.add(q.target());
        }
        if (q.types() != null && !q.types().isEmpty()) {
            sql.append(" AND type IN (");
            for (int i = 0; i < q.types().size(); i++) {
                if (i > 0) {
                    sql.append(',');
                }
                sql.append('?');
                params.add(q.types().get(i).name());
            }
            sql.append(")");
        }
        sql.append(" ORDER BY ts DESC LIMIT ?");
        params.add(q.limit() <= 0 ? 200 : Math.min(q.limit(), 5000));

        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Record(
                            rs.getLong(1),
                            RecordType.valueOf(rs.getString(2)),
                            rs.getString(3),
                            rs.getString(4)));
                }
            }
        } catch (SQLException e) {
            log.warn("查询日志失败", e);
        }
        return result;
    }

    @Override
    public long purgeOlderThan(long cutoffMillis) {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM records WHERE ts < ?")) {
            ps.setLong(1, cutoffMillis);
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("清理过期日志失败", e);
            return 0;
        }
    }

    @Override
    public long purgeOlderThan(long cutoffMillis, List<RecordType> types) {
        if (types == null || types.isEmpty()) {
            return 0;
        }
        StringBuilder sql = new StringBuilder("DELETE FROM records WHERE ts < ? AND type IN (");
        for (int i = 0; i < types.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
        }
        sql.append(')');
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setLong(idx++, cutoffMillis);
            for (RecordType t : types) {
                ps.setString(idx++, t.name());
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("按类型清理过期日志失败", e);
            return 0;
        }
    }

    @Override
    public void flush() {
        List<Record> batch = new ArrayList<>(BATCH);
        criticalQueue.drainTo(batch);
        normalQueue.drainTo(batch);
        if (batch.isEmpty()) {
            return;
        }
        // 用独立连接落盘，避免与后台写线程竞争 writeConn
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO records (ts, type, target, payload) VALUES (?, ?, ?, ?)")) {
            for (Record r : batch) {
                ps.setLong(1, r.ts());
                ps.setString(2, r.type().name());
                ps.setString(3, r.target());
                ps.setString(4, r.payload());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            log.warn("flush 落盘失败, 丢弃 {} 条", batch.size(), e);
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            writer.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            writeConn.close();
        } catch (SQLException ignored) {
            // 关闭失败忽略
        }
    }
}
