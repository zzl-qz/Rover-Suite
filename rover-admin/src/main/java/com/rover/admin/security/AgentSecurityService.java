package com.rover.admin.security;

import com.rover.admin.config.AdminProperties;
import com.rover.agent.core.port.AgentAccess;
import com.rover.agent.core.port.RouteControlPort;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
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
    private final AdminUserStore users;
    private final RouteControlPort control;
    private final String alertOwner;
    private final Connection connection;
    private final Clock clock;
    private final AdminProperties properties;
    @Value("${rover.agent.production-mode:false}") private boolean productionMode;
    @Value("${rover.agent.start-writes-stopped:false}") private boolean startWritesStopped;
    private volatile boolean closing;

    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextClosedEvent.class)
    @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
    public void beginClosing() { closing = true; }

    public record Machine(String id, String principal, String namespace, long expiresAtMillis,
                          boolean revoked, String createdBy, String purpose) { }
    public record IssuedToken(String id, String principal, String token, long expiresAtMillis) { }

    @Autowired
    public AgentSecurityService(AdminProperties properties, AdminUserStore users, RouteControlPort control,
                               @Value("${rover.agent.alerts.owner-user-id:}") String alertOwner,
                               com.rover.agent.runtime.repository.AgentStore store) {
        this(properties, users, control, alertOwner, Clock.systemUTC());
    }

    AgentSecurityService(AdminProperties properties, AdminUserStore users, RouteControlPort control,
                         String alertOwner, Clock clock) {
        this.users = users;
        this.properties = properties;
        this.control = control;
        this.alertOwner = alertOwner;
        this.clock = clock;
        try {
            connection = DriverManager.getConnection("jdbc:h2:file:" + properties.getLogStorePath()
                    + ";DB_CLOSE_DELAY=0;AUTO_SERVER=TRUE;WRITE_DELAY=0", "sa", "");
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS admin_agent_control (resource_namespace VARCHAR(64) PRIMARY KEY, stopped BOOLEAN NOT NULL)");
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
                try (PreparedStatement statement = connection.prepareStatement("MERGE INTO admin_agent_control KEY(resource_namespace) VALUES(?,TRUE)")) {
                    statement.setString(1, control.resourceNamespace()); statement.executeUpdate();
                    audit("STARTUP_WRITE_STOP", control.resourceNamespace(), "system:startup", Map.of("stopped", true));
                    return null;
                } catch (SQLException ex) { throw new IllegalStateException("启动停写失败", ex); }
            });
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

    public synchronized void stopWrites(String actor, boolean stopped) {
        require(actor, Permission.MANAGE_SYSTEM);
        transaction(() -> {
        try (PreparedStatement statement = connection.prepareStatement("MERGE INTO admin_agent_control KEY(resource_namespace) VALUES(?,?)")) {
            statement.setString(1, control.resourceNamespace()); statement.setBoolean(2, stopped); statement.executeUpdate();
            audit("WRITE_STOP_CHANGED", control.resourceNamespace(), actor, Map.of("stopped", stopped));
            return null;
        } catch (SQLException ex) { throw new IllegalStateException("保存写停开关失败", ex); }
        });
    }

    @Override public boolean writeAllowed(String principal) {
        return permissions(principal).contains(Permission.APPROVE) && !writesStopped();
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
