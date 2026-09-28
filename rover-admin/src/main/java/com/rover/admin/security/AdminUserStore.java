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

    public AdminUserStore(AdminProperties properties, PasswordEncoder encoder) {
        String dbPath = properties.getLogStorePath();
        File file = new File(dbPath);
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        String jdbcUrl = "jdbc:h2:file:" + dbPath + ";DB_CLOSE_DELAY=0;AUTO_SERVER=TRUE";
        try {
            connection = DriverManager.getConnection(jdbcUrl, "sa", "");
            ensureTable();
            seedIfEmpty(encoder);
        } catch (SQLException ex) {
            throw new IllegalStateException("打开控制台用户表失败: " + jdbcUrl, ex);
        }
        log.info("控制台用户表已就绪，默认账号 {}。库文件 {}.mv.db", DEFAULT_USERNAME, file.getAbsolutePath());
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        String hash = findHash(username);
        if (hash == null) {
            throw new UsernameNotFoundException("用户名或口令不正确");
        }
        return User.withUsername(username).password(hash).roles("ADMIN").build();
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
        }
    }

    /** 没有账号时才播种。已有任何用户就保持原样。 */
    private void seedIfEmpty(PasswordEncoder encoder) throws SQLException {
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
            insert.setString(2, encoder.encode(DEFAULT_PASSWORD));
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
