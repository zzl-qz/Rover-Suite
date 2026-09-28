package com.rover.agent.runtime.repository;

import com.rover.agent.core.model.AgentMessage;
import com.rover.agent.core.model.MessageRole;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.SessionStatus;
import com.rover.agent.core.repository.AgentMessageRepository;
import com.rover.agent.core.repository.AgentSessionRepository;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 原始聊天记录：会话一张表，消息一张表，按会话编号读取。
 *
 * 和记忆库分开。这里只留用户和 Agent 说过的原话。
 * 放在记录库同一个 H2 文件里，重启后工作台还能翻到这些原话。
 */
public final class H2TranscriptStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(H2TranscriptStore.class);

    private final Connection connection;
    private final Sessions sessions = new Sessions();
    private final Messages messages = new Messages();

    public H2TranscriptStore(String dbPath) {
        if (dbPath == null || dbPath.isBlank()) {
            throw new IllegalArgumentException("聊天记录库路径不能为空");
        }
        File file = new File(dbPath);
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        String jdbcUrl = "jdbc:h2:file:" + dbPath + ";DB_CLOSE_DELAY=0;AUTO_SERVER=TRUE";
        try {
            connection = DriverManager.getConnection(jdbcUrl, "sa", "");
            migrate();
        } catch (SQLException ex) {
            throw new IllegalStateException("打开聊天记录库失败: " + jdbcUrl, ex);
        }
        log.info("聊天记录已就绪: {}.mv.db", file.getAbsolutePath());
    }

    public AgentSessionRepository sessions() {
        return sessions;
    }

    public AgentMessageRepository messages() {
        return messages;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ex) {
            log.warn("关闭聊天记录库失败", ex);
        }
    }

    private void migrate() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS chat_session ("
                    + "session_id VARCHAR(64) PRIMARY KEY, "
                    + "user_id VARCHAR(128), "
                    + "title VARCHAR(200), "
                    + "active_incident_id VARCHAR(64), "
                    + "status VARCHAR(16) NOT NULL, "
                    + "created_at BIGINT NOT NULL, "
                    + "last_active_at BIGINT NOT NULL, "
                    + "incident_ids CLOB)");
            statement.execute("CREATE TABLE IF NOT EXISTS chat_message ("
                    + "message_id VARCHAR(64) PRIMARY KEY, "
                    + "session_id VARCHAR(64) NOT NULL, "
                    + "role VARCHAR(16) NOT NULL, "
                    + "content CLOB NOT NULL, "
                    + "created_at BIGINT NOT NULL, "
                    + "related_task_id VARCHAR(64))");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_chat_message_session "
                    + "ON chat_message(session_id, created_at)");
        }
    }

    private final class Sessions implements AgentSessionRepository {

        @Override
        public void save(Session session) {
            synchronized (H2TranscriptStore.this) {
            String sql = "MERGE INTO chat_session (session_id, user_id, title, active_incident_id, status, "
                    + "created_at, last_active_at, incident_ids) KEY (session_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, session.sessionId());
                statement.setString(2, blankToNull(session.userId()));
                statement.setString(3, session.title());
                statement.setString(4, blankToNull(session.activeIncidentId()));
                statement.setString(5, session.status().name());
                statement.setLong(6, session.createdAtMillis());
                statement.setLong(7, session.lastActiveAtMillis());
                statement.setString(8, String.join(",", session.incidentIds()));
                statement.executeUpdate();
            } catch (SQLException ex) {
                throw new IllegalStateException("保存会话失败", ex);
            }
            }
        }

        @Override
        public Optional<Session> find(String sessionId) {
            return query("SELECT * FROM chat_session WHERE session_id = ?", sessionId).stream().findFirst();
        }

        @Override
        public List<Session> findByUserId(String userId) {
            if (userId == null || userId.isBlank()) {
                return query("SELECT * FROM chat_session WHERE user_id IS NULL ORDER BY created_at");
            }
            return query("SELECT * FROM chat_session WHERE user_id = ? ORDER BY created_at", userId);
        }

        @Override
        public List<Session> listAll() {
            return query("SELECT * FROM chat_session ORDER BY created_at");
        }

        @Override
        public void remove(String sessionId) {
            update("DELETE FROM chat_session WHERE session_id = ?", sessionId);
        }
    }

    private final class Messages implements AgentMessageRepository {

        @Override
        public void save(AgentMessage message) {
            synchronized (H2TranscriptStore.this) {
            String sql = "MERGE INTO chat_message (message_id, session_id, role, content, created_at, related_task_id) "
                    + "KEY (message_id) VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, message.messageId());
                statement.setString(2, message.sessionId());
                statement.setString(3, message.role().name());
                statement.setString(4, message.content());
                statement.setLong(5, message.createdAtMillis());
                statement.setString(6, blankToNull(message.relatedTaskId()));
                statement.executeUpdate();
            } catch (SQLException ex) {
                throw new IllegalStateException("保存消息失败", ex);
            }
            }
        }

        @Override
        public List<AgentMessage> bySession(String sessionId) {
            return messages("SELECT * FROM chat_message WHERE session_id = ? ORDER BY created_at", sessionId);
        }

        @Override
        public List<AgentMessage> listAll() {
            return messages("SELECT * FROM chat_message ORDER BY created_at");
        }

        @Override
        public Optional<AgentMessage> oldest() {
            return messages("SELECT * FROM chat_message ORDER BY created_at LIMIT 1").stream().findFirst();
        }

        @Override
        public void remove(String messageId) {
            update("DELETE FROM chat_message WHERE message_id = ?", messageId);
        }

        @Override
        public int removeBySession(String sessionId) {
            return update("DELETE FROM chat_message WHERE session_id = ?", sessionId);
        }
    }

    private synchronized List<Session> query(String sql, String... args) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet rows = statement.executeQuery()) {
                List<Session> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(readSession(rows));
                }
                return List.copyOf(found);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("读取会话失败", ex);
        }
    }

    private synchronized List<AgentMessage> messages(String sql, String... args) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet rows = statement.executeQuery()) {
                List<AgentMessage> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(readMessage(rows));
                }
                return List.copyOf(found);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("读取消息失败", ex);
        }
    }

    private synchronized int update(String sql, String id) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            return statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("删除聊天记录失败", ex);
        }
    }

    private static void bind(PreparedStatement statement, String... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            statement.setString(i + 1, args[i]);
        }
    }

    private static Session readSession(ResultSet rows) throws SQLException {
        String incidents = rows.getString("incident_ids");
        List<String> incidentIds = incidents == null || incidents.isBlank()
                ? List.of()
                : Arrays.stream(incidents.split(",")).filter(id -> !id.isBlank()).toList();
        return new Session(rows.getString("session_id"), blankToNull(rows.getString("user_id")),
                rows.getString("title"), blankToNull(rows.getString("active_incident_id")),
                SessionStatus.valueOf(rows.getString("status")), rows.getLong("created_at"),
                rows.getLong("last_active_at"), incidentIds);
    }

    private static AgentMessage readMessage(ResultSet rows) throws SQLException {
        return new AgentMessage(rows.getString("message_id"), rows.getString("session_id"),
                MessageRole.valueOf(rows.getString("role")), rows.getString("content"),
                rows.getLong("created_at"), blankToNull(rows.getString("related_task_id")));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
