package com.rover.admin.security;

import com.rover.admin.config.AdminProperties;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 控制台账号存在记录库同一份 H2 里的 {@code admin_user} 表。
 *
 * 目前只有一个共用账号：表是空的就写入 admin / admin。已有记录不覆盖，避免重启把口令打回默认值。
 */
@Component
public class AdminUserStore implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserStore.class);

    /** 首次建库时写入的共用账号。 */
    public static final String DEFAULT_USERNAME = "admin";

    /** 首次建库时写入的共用口令，库里只存哈希。 */
    public static final String DEFAULT_PASSWORD = "admin";

    private final Connection connection;
    private final PasswordEncoder encoder;

    public record Account(String username, List<String> roles, String namespace, boolean enabled) { }

    public AdminUserStore(AdminProperties properties, PasswordEncoder encoder) {
        this.encoder = encoder;
        String dbPath = properties.getLogStorePath();
        File file = new File(dbPath);
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        String jdbcUrl = "jdbc:h2:file:" + dbPath + ";DB_CLOSE_DELAY=0;AUTO_SERVER=TRUE";
        try {
            connection = DriverManager.getConnection(jdbcUrl, "sa", "");
            ensureTable();
            seedIfEmpty(encoder, properties.getBootstrapPassword());
        } catch (SQLException ex) {
            throw new IllegalStateException("打开控制台用户表失败: " + jdbcUrl, ex);
        }
        log.info("控制台用户表已就绪，默认账号 {}。库文件 {}.mv.db", DEFAULT_USERNAME, file.getAbsolutePath());
    }

    @Override
    public synchronized UserDetails loadUserByUsername(String username) {
        String hash = findHash(username);
        Account account = account(username);
        if (hash == null || account == null || !account.enabled()) {
            throw new UsernameNotFoundException("用户名或口令不正确");
        }
        return User.withUsername(username).password(hash).roles(account.roles().toArray(String[]::new)).build();
    }

    @PreDestroy
    public void close() {
        try {
            connection.close();
        } catch (SQLException ex) {
            log.warn("关闭控制台用户表连接失败", ex);
        }
    }

    private void ensureTable() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS admin_user ("
                    + "username VARCHAR(64) PRIMARY KEY, "
                    + "password_hash VARCHAR(100) NOT NULL)");
            statement.execute("ALTER TABLE admin_user ADD COLUMN IF NOT EXISTS roles VARCHAR(200) DEFAULT 'ADMIN' NOT NULL");
            statement.execute("ALTER TABLE admin_user ADD COLUMN IF NOT EXISTS resource_namespace VARCHAR(64)");
            statement.execute("ALTER TABLE admin_user ADD COLUMN IF NOT EXISTS enabled BOOLEAN DEFAULT TRUE NOT NULL");
        }
    }

    public synchronized Account account(String username) {
        if (username == null) { return null; }
        try (PreparedStatement statement = connection.prepareStatement("SELECT username,roles,resource_namespace,enabled FROM admin_user WHERE username=?")) {
            statement.setString(1, username);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? readAccount(rows) : null;
            }
        } catch (SQLException ex) { throw new IllegalStateException("读取身份权限失败", ex); }
    }

    boolean usesDefaultPassword() {
        String hash = findHash(DEFAULT_USERNAME);
        return hash != null && encoder.matches(DEFAULT_PASSWORD, hash);
    }

    public synchronized List<Account> accounts() {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT username,roles,resource_namespace,enabled FROM admin_user ORDER BY username")) {
            java.util.ArrayList<Account> result = new java.util.ArrayList<>();
            while (rows.next()) { result.add(readAccount(rows)); }
            return List.copyOf(result);
        } catch (SQLException ex) { throw new IllegalStateException("读取账号清单失败", ex); }
    }

    /** 受控管理入口；密码只保存 BCrypt 哈希，权限变更由每次请求/执行实时读取。 */
    public synchronized Account saveAccount(String username, String password, Set<String> roles,
                                             String namespace, boolean enabled) {
        if (username == null || !username.matches("[A-Za-z0-9._-]{1,64}") || roles == null || roles.isEmpty()
                || !Set.of("ADMIN", "VIEWER", "OPERATOR", "APPROVER", "POLICY_ADMIN").containsAll(roles)) {
            throw new IllegalArgumentException("账号标识或角色不合法");
        }
        Account previous = account(username);
        if (previous != null && previous.enabled() && previous.roles().contains("ADMIN")
                && (!enabled || !roles.contains("ADMIN")) && accounts().stream()
                .noneMatch(other -> !other.username().equals(username) && other.enabled() && other.roles().contains("ADMIN"))) {
            throw new IllegalArgumentException("不能停用或降权最后一个管理员");
        }
        String hash = findHash(username);
        if (password != null && !password.isBlank()) {
            if (password.length() < 12 || password.length() > 256) { throw new IllegalArgumentException("新口令长度须为 12~256 字符"); }
            hash = encoder.encode(password);
        }
        if (hash == null) { throw new IllegalArgumentException("新账号必须提供口令"); }
        final String passwordHash = hash;
        return transaction(() -> {
        try (PreparedStatement statement = connection.prepareStatement("MERGE INTO admin_user (username,password_hash,roles,resource_namespace,enabled) KEY(username) VALUES(?,?,?,?,?)")) {
            statement.setString(1, username);
            statement.setString(2, passwordHash);
            statement.setString(3, roles.stream().sorted().collect(java.util.stream.Collectors.joining(",")));
            statement.setString(4, namespace);
            statement.setBoolean(5, enabled);
            statement.executeUpdate();
            Account saved = account(username);
            try (PreparedStatement audit = connection.prepareStatement("INSERT INTO agent_audit(event_kind,aggregate_id,actor,resource_namespace,payload_json,created_at) VALUES(?,?,?,?,?,?)")) {
                audit.setString(1, "ACCOUNT_CHANGED"); audit.setString(2, username);
                audit.setString(3, com.rover.agent.core.util.AuditActor.current("local-account-management"));
                audit.setString(4, namespace);
                audit.setString(5, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(saved));
                audit.setLong(6, System.currentTimeMillis()); audit.executeUpdate();
            }
            return saved;
        } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("保存身份权限及审计失败", ex); }
        });
    }

    private <T> T transaction(java.util.function.Supplier<T> operation) {
        try {
            connection.setAutoCommit(false);
            try { T result = operation.get(); connection.commit(); return result; }
            catch (RuntimeException error) { connection.rollback(); throw error; }
            finally { connection.setAutoCommit(true); }
        } catch (SQLException error) { throw new IllegalStateException("账号状态事务失败", error); }
    }

    private static Account readAccount(ResultSet rows) throws SQLException {
        return new Account(rows.getString("username"), Arrays.stream(rows.getString("roles").split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).toList(), rows.getString("resource_namespace"), rows.getBoolean("enabled"));
    }

    /** 没有账号时才播种。已有任何用户就保持原样。 */
    private void seedIfEmpty(PasswordEncoder encoder, String bootstrapPassword) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet count = statement.executeQuery("SELECT COUNT(*) FROM admin_user")) {
            count.next();
            if (count.getInt(1) > 0) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO admin_user (username, password_hash) VALUES (?, ?)")) {
            insert.setString(1, DEFAULT_USERNAME);
            if (bootstrapPassword != null && !bootstrapPassword.isBlank() && bootstrapPassword.length() < 12) {
                throw new IllegalArgumentException("引导口令至少 12 字符");
            }
            insert.setString(2, encoder.encode(bootstrapPassword == null || bootstrapPassword.isBlank() ? DEFAULT_PASSWORD : bootstrapPassword));
            insert.executeUpdate();
        }
        log.info("已写入默认控制台账号 {}，口令为初始值，请登录后知悉多人共用这一账号", DEFAULT_USERNAME);
    }

    private String findHash(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT password_hash FROM admin_user WHERE username = ?")) {
            statement.setString(1, username.trim());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("查询控制台用户失败", ex);
        }
    }
}
