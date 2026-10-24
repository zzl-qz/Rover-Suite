package com.rover.admin.security;

import com.rover.admin.config.AdminProperties;
import com.rover.agent.core.port.AgentAccess;
import com.rover.agent.core.port.RouteControlPort;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 当前单进程的实时账号授权及仅限告警入口的机器令牌。秘密只在创建时返回一次。 */
@Service
public class AgentSecurityService implements AgentAccess {
    private static final long WRITE_RESUME_APPROVAL_WINDOW_MILLIS = Duration.ofMinutes(15).toMillis();
    private static final int WRITE_RESUME_REQUIRED_APPROVALS = 2;
    private static final String WRITE_RESUME_POLICY_VERSION = "write-resume-v1";
    private final AdminUserStore users;
    private final RouteControlPort control;
    private final String alertOwner;
    private final Connection connection;
    private final Clock clock;
    private final AdminProperties properties;
    private final com.rover.agent.runtime.repository.AgentStore store;
    private final java.util.concurrent.locks.ReentrantLock writeGate = new java.util.concurrent.locks.ReentrantLock(true);
    private final java.util.concurrent.atomic.AtomicInteger admittedWrites = new java.util.concurrent.atomic.AtomicInteger();
    @Value("${rover.agent.production-mode:false}") private boolean productionMode;
    @Value("${rover.agent.start-writes-stopped:false}") private boolean startWritesStopped;
    private volatile boolean closing;

    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextClosedEvent.class)
    @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
    public void beginClosing() { closing = true; }

    public record Machine(String id, String principal, String namespace, long expiresAtMillis,
                          boolean revoked, String createdBy, String purpose) { }
    public record IssuedToken(String id, String principal, String token, long expiresAtMillis) { }
    public record WriteResumeState(boolean stopped, long generation, int approvals, int requiredApprovals,
                                   List<String> approvers, long expiresAtMillis) {
        public WriteResumeState { approvers = List.copyOf(approvers); }
    }

    @Autowired
    public AgentSecurityService(AdminProperties properties, AdminUserStore users, RouteControlPort control,
                               @Value("${rover.agent.alerts.owner-user-id:}") String alertOwner,
                               com.rover.agent.runtime.repository.AgentStore store) {
        this(properties, users, control, alertOwner, Clock.systemUTC(), store);
    }

    AgentSecurityService(AdminProperties properties, AdminUserStore users, RouteControlPort control,
                         String alertOwner, Clock clock) {
        this(properties, users, control, alertOwner, clock, null);
    }

    AgentSecurityService(AdminProperties properties, AdminUserStore users, RouteControlPort control,
                         String alertOwner, Clock clock, com.rover.agent.runtime.repository.AgentStore store) {
        this.users = users;
        this.properties = properties;
        this.control = control;
        this.alertOwner = alertOwner;
        this.clock = clock;
        this.store = store;
        try {
            File dbFile = new File(properties.getLogStorePath());
            if (dbFile.getParentFile() != null) { dbFile.getParentFile().mkdirs(); }
            connection = DriverManager.getConnection("jdbc:h2:file:" + properties.getLogStorePath()
                    + ";DB_CLOSE_DELAY=0;AUTO_SERVER=TRUE;WRITE_DELAY=0", "sa", "");
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS admin_agent_control (resource_namespace VARCHAR(64) PRIMARY KEY, stopped BOOLEAN NOT NULL, stop_generation BIGINT NOT NULL DEFAULT 0)");
                statement.execute("ALTER TABLE admin_agent_control ADD COLUMN IF NOT EXISTS stop_generation BIGINT NOT NULL DEFAULT 0");
                statement.execute("CREATE TABLE IF NOT EXISTS admin_agent_write_resume (resource_namespace VARCHAR(64) NOT NULL, stop_generation BIGINT NOT NULL, approver VARCHAR(128) NOT NULL, policy_version VARCHAR(32) NOT NULL, approved_at BIGINT NOT NULL, PRIMARY KEY(resource_namespace, stop_generation, approver))");
                statement.execute("ALTER TABLE admin_agent_write_resume ADD COLUMN IF NOT EXISTS policy_version VARCHAR(32) DEFAULT 'write-resume-v1' NOT NULL");
                statement.execute("CREATE TABLE IF NOT EXISTS admin_agent_token (token_id VARCHAR(64) PRIMARY KEY, "
                        + "principal VARCHAR(128) NOT NULL, token_hash VARCHAR(64) UNIQUE NOT NULL, "
                        + "resource_namespace VARCHAR(64) NOT NULL, expires_at BIGINT NOT NULL, revoked BOOLEAN NOT NULL, "
                        + "created_by VARCHAR(128) NOT NULL)");
                statement.execute("ALTER TABLE admin_agent_token ADD COLUMN IF NOT EXISTS purpose VARCHAR(16) DEFAULT 'ALERT' NOT NULL");
            }
        } catch (SQLException ex) { throw new IllegalStateException("打开机器身份存储失败", ex); }
    }

    @PostConstruct void validateProduction() {
        if (startWritesStopped) {
            transaction(() -> {
                try { ensureControlRow(); }
                catch (SQLException ex) { throw new IllegalStateException("启动停写失败", ex); }
                try {
                    try (PreparedStatement statement = connection.prepareStatement("UPDATE admin_agent_control SET stopped=TRUE,stop_generation=stop_generation+1 WHERE resource_namespace=?")) {
                        statement.setString(1, control.resourceNamespace()); statement.executeUpdate();
                    }
                    audit("STARTUP_WRITE_STOP", control.resourceNamespace(), "system:startup", Map.of("stopped", true));
                    return null;
                } catch (SQLException ex) { throw new IllegalStateException("启动停写失败", ex); }
                });
            pausePreauthorizations("system:startup");
        }
        if (!productionMode) { return; }
        if (users.usesDefaultPassword()) {
            throw new IllegalStateException("生产模式禁止默认口令；首次建库请设置 ROVER_ADMIN_BOOTSTRAP_PASSWORD");
        }
        if (properties.getAdminToken() == null || properties.getAdminToken().isBlank()
                || properties.getGatewayControlTargetId() == null || properties.getGatewayControlTargetId().isBlank()
                || "local".equals(properties.getAgentEnvironmentId())) {
            throw new IllegalStateException("生产模式必须配置管理口凭证、稳定控制目标 ID 和明确环境");
        }
    }

    public synchronized boolean writesStopped() {
        if (closing) { return true; }
        try (PreparedStatement statement = connection.prepareStatement("SELECT stopped FROM admin_agent_control WHERE resource_namespace=?")) {
            statement.setString(1, control.resourceNamespace());
            try (ResultSet rows = statement.executeQuery()) { return rows.next() && rows.getBoolean(1); }
        } catch (SQLException ex) { throw new IllegalStateException("读取写停开关失败，禁止写入", ex); }
    }

    public void stopWrites(String actor, boolean stopped) {
        require(actor, Permission.MANAGE_SYSTEM);
        if (!stopped) { throw new IllegalStateException("恢复生产写入必须经过两名不同系统管理员审批"); }
        boolean wasStopped = writesStopped();
        writeGate.lock();
        try {
            transaction(() -> {
        try {
            ensureControlRow();
            try (PreparedStatement statement = connection.prepareStatement("UPDATE admin_agent_control SET stopped=TRUE,stop_generation=stop_generation+1 WHERE resource_namespace=?")) {
                statement.setString(1, control.resourceNamespace()); statement.executeUpdate();
            }
            audit("WRITE_STOP_CHANGED", control.resourceNamespace(), actor, Map.of("stopped", true));
            return null;
        } catch (SQLException ex) { throw new IllegalStateException("保存写停开关失败", ex); }
        });
        } finally { writeGate.unlock(); }
        if (!wasStopped) { pausePreauthorizations(actor); }
    }

    private void pausePreauthorizations(String actor) {
        if (store == null) { return; }
        long now = clock.millis();
        for (var policy : store.autoExecutionPolicies().list(control.resourceNamespace())) {
            if (!policy.enabled()) { continue; }
            try {
                store.autoExecutionPolicies().save(policy.revise(false, policy.maxBeforeTrafficPercent(),
                        policy.maxTrafficShiftPercent(), policy.expiresAtMillis(), actor, now), policy.version());
            } catch (RuntimeException failure) {
                throw new IllegalStateException("停写已生效，但暂停预授权失败；恢复该策略前必须人工核验", failure);
            }
        }
    }

    private void ensureControlRow() throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM admin_agent_control WHERE resource_namespace=?")) {
            query.setString(1, control.resourceNamespace());
            try (ResultSet rows = query.executeQuery()) { if (rows.next()) { return; } }
        }
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO admin_agent_control(resource_namespace,stopped,stop_generation) VALUES(?,TRUE,0)")) {
            insert.setString(1, control.resourceNamespace()); insert.executeUpdate();
        }
    }

    public synchronized boolean writeResumeApproverAllowed(String principal) {
        return isCurrentSystemManager(principal);
    }

    /** Two distinct, currently enabled system managers must approve the same stop generation within 15 minutes. */
    public WriteResumeState approveWriteResume(String actor, long outstandingActions) {
        writeGate.lock();
        try {
        requireCurrentSystemManager(actor);
        if (admittedWrites.get() > 0) {
            throw new IllegalStateException("仍有停写前已获准的 Gateway 请求在途；完成回读或对账后再审批恢复");
        }
        if (outstandingActions > 0) {
            throw new IllegalStateException("仍有 " + outstandingActions + " 条排队、执行中或待对账动作；先完成对账后再审批恢复");
        }
        return transaction(() -> {
            long generation;
            try (PreparedStatement statement = connection.prepareStatement("SELECT stop_generation FROM admin_agent_control WHERE resource_namespace=? AND stopped=TRUE")) {
                statement.setString(1, control.resourceNamespace());
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) { throw new IllegalStateException("当前没有停写状态，无需恢复审批"); }
                    generation = rows.getLong(1);
                }
            } catch (SQLException ex) { throw new IllegalStateException("读取停写代次失败，保持停写", ex); }
            long now = clock.millis();
            try (PreparedStatement insert = connection.prepareStatement("MERGE INTO admin_agent_write_resume(resource_namespace,stop_generation,approver,policy_version,approved_at) KEY(resource_namespace,stop_generation,approver) VALUES(?,?,?,?,?)")) {
                insert.setString(1, control.resourceNamespace()); insert.setLong(2, generation);
                insert.setString(3, actor); insert.setString(4, WRITE_RESUME_POLICY_VERSION); insert.setLong(5, now);
                insert.executeUpdate();
                audit("WRITE_RESUME_APPROVED", control.resourceNamespace(), actor, Map.of("generation", generation));
            } catch (SQLException ex) { throw new IllegalStateException("恢复审批保存失败，继续保持停写", ex); }
            WriteResumeState state = readWriteResumeState(generation, now);
            if (state.approvals() >= WRITE_RESUME_REQUIRED_APPROVALS
                    && new HashSet<>(state.approvers()).size() >= WRITE_RESUME_REQUIRED_APPROVALS) {
                try (PreparedStatement update = connection.prepareStatement("UPDATE admin_agent_control SET stopped=FALSE WHERE resource_namespace=? AND stopped=TRUE AND stop_generation=?")) {
                    update.setString(1, control.resourceNamespace()); update.setLong(2, generation);
                    if (update.executeUpdate() != 1) { throw new IllegalStateException("停写状态已变化，恢复被拒绝"); }
                } catch (SQLException ex) { throw new IllegalStateException("恢复写入失败，继续保持停写", ex); }
                audit("WRITE_RESUME_COMPLETED", control.resourceNamespace(), actor,
                        Map.of("generation", generation, "approvers", state.approvers()));
                return new WriteResumeState(false, generation, state.approvals(), state.requiredApprovals(),
                        state.approvers(), state.expiresAtMillis());
            }
            return state;
        });
        } finally { writeGate.unlock(); }
    }

    public synchronized WriteResumeState writeResumeState() {
        try (PreparedStatement statement = connection.prepareStatement("SELECT stopped,stop_generation FROM admin_agent_control WHERE resource_namespace=?")) {
            statement.setString(1, control.resourceNamespace());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) { return new WriteResumeState(false, 0, 0, WRITE_RESUME_REQUIRED_APPROVALS, List.of(), 0); }
                return readWriteResumeState(rows.getLong(2), clock.millis());
            }
        } catch (SQLException ex) { throw new IllegalStateException("读取恢复审批状态失败，保持当前写入策略", ex); }
    }

    private WriteResumeState readWriteResumeState(long generation, long now) {
        long cutoff = now - WRITE_RESUME_APPROVAL_WINDOW_MILLIS;
        List<String> approvers = new ArrayList<>();
        long expiresAt = Long.MAX_VALUE;
        try (PreparedStatement statement = connection.prepareStatement("SELECT approver,approved_at FROM admin_agent_write_resume WHERE resource_namespace=? AND stop_generation=? AND policy_version=? AND approved_at>=? ORDER BY approved_at")) {
            statement.setString(1, control.resourceNamespace()); statement.setLong(2, generation);
            statement.setString(3, WRITE_RESUME_POLICY_VERSION); statement.setLong(4, cutoff);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String approver = rows.getString(1);
                    if (!isCurrentSystemManager(approver)) { continue; }
                    approvers.add(approver);
                    expiresAt = Math.min(expiresAt, rows.getLong(2) + WRITE_RESUME_APPROVAL_WINDOW_MILLIS);
                }
            }
        } catch (SQLException ex) { throw new IllegalStateException("读取恢复审批失败，保持停写", ex); }
        long count; boolean stopped;
        try (PreparedStatement statement = connection.prepareStatement("SELECT stopped FROM admin_agent_control WHERE resource_namespace=? AND stop_generation=?")) {
            statement.setString(1, control.resourceNamespace()); statement.setLong(2, generation);
            try (ResultSet rows = statement.executeQuery()) { stopped = rows.next() && rows.getBoolean(1); }
        } catch (SQLException ex) { throw new IllegalStateException("读取停写状态失败", ex); }
        count = approvers.size();
        return new WriteResumeState(stopped, generation, (int) count, WRITE_RESUME_REQUIRED_APPROVALS,
                approvers, expiresAt == Long.MAX_VALUE ? 0 : expiresAt);
    }

    private void requireCurrentSystemManager(String principal) {
        if (!isCurrentSystemManager(principal)) {
            throw new Denied("恢复写入审批要求当前有效的系统管理员权限");
        }
    }

    private boolean isCurrentSystemManager(String principal) {
        AdminUserStore.Account account = users.account(principal);
        return account != null && account.enabled() && account.roles().contains("ADMIN")
                && (Objects.equals(account.namespace(), control.resourceNamespace()) || account.namespace() == null);
    }

    @Override public boolean writeAllowed(String principal) {
        return permissions(principal).contains(Permission.APPROVE) && !writesStopped();
    }

    @Override public boolean automatedWriteAllowed() {
        return !writesStopped() && store != null && store.persistent() && store.durable().healthy();
    }

    @Override public WritePermit acquireWritePermit(String principal, boolean automated) {
        return acquirePermit(() -> automated ? automatedWriteAllowed() : writeAllowed(principal));
    }

    public WritePermit acquirePolicyPermit(String principal) {
        return acquirePermit(() -> permissions(principal).contains(Permission.POLICY_ADMIN)
                && !writesStopped() && store != null && store.persistent() && store.durable().healthy());
    }

    private WritePermit acquirePermit(java.util.function.BooleanSupplier allowed) {
        writeGate.lock();
        boolean granted = false;
        try {
            if (allowed.getAsBoolean()) {
                admittedWrites.incrementAndGet();
                granted = true;
                return () -> {
                    admittedWrites.decrementAndGet();
                    writeGate.unlock();
                };
            }
            return null;
        } finally { if (!granted) { writeGate.unlock(); } }
    }

    @Override
    public Set<Permission> permissions(String principal) {
        AdminUserStore.Account account = users.account(principal);
        if (account == null || !account.enabled()) { return Set.of(); }
        if (!Objects.equals(account.namespace(), control.resourceNamespace())
                && !(account.namespace() == null && account.roles().contains("ADMIN"))) { return Set.of(); }
        EnumSet<Permission> permissions = EnumSet.noneOf(Permission.class);
        for (String role : account.roles()) {
            switch (role) {
                case "ADMIN" -> permissions.addAll(Set.of(Permission.values()));
                case "VIEWER" -> permissions.add(Permission.READ);
                case "OPERATOR" -> permissions.addAll(Set.of(Permission.READ, Permission.INVESTIGATE, Permission.PROPOSE, Permission.INGEST));
                case "APPROVER" -> permissions.addAll(Set.of(Permission.READ, Permission.APPROVE));
                case "POLICY_ADMIN" -> permissions.addAll(Set.of(Permission.READ, Permission.POLICY_ADMIN));
                default -> { }
            }
        }
        return Set.copyOf(permissions);
    }

    public AdminUserStore.Account account(String username) { return users.account(username); }

    /** Instance isolation/restore changes live traffic and is reserved for an enabled system administrator. */
    public void requireInstanceIsolationManager(String principal) {
        AdminUserStore.Account account = users.account(principal);
        if (account == null || !account.enabled() || !account.roles().contains("ADMIN")
                || (account.namespace() != null && !Objects.equals(account.namespace(), control.resourceNamespace()))
                || !permissions(principal).contains(Permission.APPROVE) || writesStopped()) {
            throw new AgentAccess.Denied("实例隔离和恢复仅允许当前管理范围内的系统管理员执行");
        }
    }

    public List<AdminUserStore.Account> accounts(String actor) {
        require(actor, Permission.MANAGE_SYSTEM);
        return users.accounts();
    }

    public AdminUserStore.Account saveAccount(String actor, String username, String password, Set<String> roles, boolean enabled) {
        require(actor, Permission.MANAGE_SYSTEM);
        return users.saveAccount(username, password, roles, control.resourceNamespace(), enabled);
    }

    public synchronized IssuedToken issue(String actor, String name, Long expiresAtMillis) {
        return issue(actor, name, expiresAtMillis, "ALERT");
    }

    public synchronized IssuedToken issue(String actor, String name, Long expiresAtMillis, String purpose) {
        require(actor, Permission.POLICY_ADMIN);
        String tokenPurpose = purpose == null ? "ALERT" : purpose;
        if (!Set.of("ALERT", "RELEASE").contains(tokenPurpose)) { throw new IllegalArgumentException("令牌用途必须为 ALERT 或 RELEASE"); }
        if (tokenPurpose.equals("ALERT")) { requireHandler(); }
        if (name == null || !name.matches("[A-Za-z0-9._-]{1,64}")) { throw new IllegalArgumentException("机器来源名称不合法"); }
        long expiry = expiresAtMillis == null ? clock.millis() + Duration.ofDays(30).toMillis() : expiresAtMillis;
        if (expiry <= clock.millis() || expiry - clock.millis() > Duration.ofDays(365).toMillis()) {
            throw new IllegalArgumentException("令牌有效期必须在未来一年以内");
        }
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String secret = "rvr_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String id = UUID.randomUUID().toString();
        String principal = "connector:" + name;
        return transaction(() -> {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO admin_agent_token(token_id,principal,token_hash,resource_namespace,expires_at,revoked,created_by,purpose) VALUES(?,?,?,?,?,?,?,?)")) {
            statement.setString(1, id); statement.setString(2, principal); statement.setString(3, digest(secret));
            statement.setString(4, control.resourceNamespace()); statement.setLong(5, expiry);
            statement.setBoolean(6, false); statement.setString(7, actor); statement.setString(8, tokenPurpose); statement.executeUpdate();
            audit("CONNECTOR_ISSUED", id, actor, Map.of("principal", principal, "expiresAtMillis", expiry, "purpose", tokenPurpose));
            return new IssuedToken(id, principal, secret, expiry);
        } catch (SQLException ex) { throw new IllegalStateException("创建机器身份失败", ex); }
        });
    }

    public synchronized Machine authenticate(String token) {
        if (token == null || token.length() > 256 || !token.startsWith("rvr_")) { return null; }
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM admin_agent_token WHERE token_hash=?")) {
            statement.setString(1, digest(token));
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) { return null; }
                Machine machine = read(row);
                if (!Set.of("ALERT", "RELEASE").contains(machine.purpose()) || machine.revoked() || machine.expiresAtMillis() <= clock.millis()
                        || !machine.namespace().equals(control.resourceNamespace())) { return null; }
                if (machine.purpose().equals("ALERT")) { requireHandler(); }
                return machine;
            }
        } catch (SQLException ex) { throw new IllegalStateException("读取机器身份失败", ex); }
    }

    public synchronized List<Machine> machines(String actor) {
        require(actor, Permission.POLICY_ADMIN);
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT * FROM admin_agent_token ORDER BY token_id")) {
            List<Machine> result = new ArrayList<>();
            while (rows.next()) { result.add(read(rows)); }
            return List.copyOf(result);
        } catch (SQLException ex) { throw new IllegalStateException("读取机器身份列表失败", ex); }
    }

    public synchronized boolean revoke(String actor, String id) {
        require(actor, Permission.POLICY_ADMIN);
        return transaction(() -> {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE admin_agent_token SET revoked=TRUE WHERE token_id=?")) {
            statement.setString(1, id);
            boolean changed = statement.executeUpdate() == 1;
            if (changed) { audit("CONNECTOR_REVOKED", id, actor, Map.of("id", id)); }
            return changed;
        } catch (SQLException ex) { throw new IllegalStateException("撤销机器身份失败", ex); }
        });
    }

    private void audit(String kind, String id, String actor, Map<String,?> payload) {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO agent_audit(event_kind,aggregate_id,actor,resource_namespace,payload_json,created_at) VALUES(?,?,?,?,?,?)")) {
            statement.setString(1, kind); statement.setString(2, id); statement.setString(3, actor);
            statement.setString(4, control.resourceNamespace());
            statement.setString(5, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload));
            statement.setLong(6, clock.millis()); statement.executeUpdate();
        } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("持久安全审计失败", ex); }
    }

    private <T> T transaction(java.util.function.Supplier<T> operation) {
        try {
            connection.setAutoCommit(false);
            try { T result = operation.get(); connection.commit(); return result; }
            catch (RuntimeException error) { connection.rollback(); throw error; }
            finally { connection.setAutoCommit(true); }
        } catch (SQLException error) { throw new IllegalStateException("安全状态事务失败", error); }
    }

    private void requireHandler() {
        if (alertOwner == null || alertOwner.isBlank()
                || !permissions(alertOwner).contains(Permission.INVESTIGATE)) {
            throw new Denied("机器告警必须配置有效的 rover.agent.alerts.owner-user-id，来源身份不能兼任默认处理账号");
        }
    }

    private static Machine read(ResultSet row) throws SQLException {
        return new Machine(row.getString("token_id"), row.getString("principal"), row.getString("resource_namespace"),
                row.getLong("expires_at"), row.getBoolean("revoked"), row.getString("created_by"), row.getString("purpose"));
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    @PreDestroy public synchronized void close() {
        try { connection.close(); } catch (SQLException ex) { throw new IllegalStateException(ex); }
    }
}
