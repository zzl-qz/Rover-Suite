package com.rover.agent.runtime.journal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rover.agent.core.journal.ResourceNote;
import com.rover.agent.core.model.TaskStatus;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.repository.AgentTaskRepository;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 调查快照和资源笔记，放在记录库同一个 H2 文件里。
 *
 * 快照给工作台回看过程。资源笔记只在有已确认根因时由 {@link OpsJournal} 写入。
 */
public final class H2InvestigationLog implements AgentTaskRepository, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(H2InvestigationLog.class);
    private static final String INTERRUPTED = "Admin 重启，调查已中断";
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Connection connection;

    public H2InvestigationLog(String dbPath) {
        if (dbPath == null || dbPath.isBlank()) {
            throw new IllegalArgumentException("调查记录库路径不能为空");
        }
        File file = new File(dbPath);
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        String jdbcUrl = "jdbc:h2:file:" + dbPath + ";DB_CLOSE_DELAY=0;AUTO_SERVER=TRUE";
        try {
            connection = DriverManager.getConnection(jdbcUrl, "sa", "");
            migrate();
            interruptLeftovers();
        } catch (SQLException ex) {
            throw new IllegalStateException("打开调查记录库失败: " + jdbcUrl, ex);
        }
        log.info("调查记录已就绪: {}.mv.db", file.getAbsolutePath());
    }

    public synchronized Optional<ResourceNote> findNote(String resourceKey) {
        if (resourceKey == null || resourceKey.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT * FROM resource_note WHERE resource_key = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, resourceKey);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ResourceNote(rows.getString("resource_key"), rows.getString("symptom"),
                        rows.getString("root_cause"), rows.getString("useful_steps"), rows.getString("pitfalls"),
                        rows.getString("source_session_id"), rows.getString("source_task_id"),
                        rows.getLong("updated_at")));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("读取资源笔记失败", ex);
        }
    }

    public synchronized void saveNote(ResourceNote note) {
        String sql = "MERGE INTO resource_note (resource_key, symptom, root_cause, useful_steps, pitfalls, "
                + "source_session_id, source_task_id, updated_at) KEY (resource_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, note.resourceKey());
            statement.setString(2, note.symptom());
            statement.setString(3, note.rootCause());
            statement.setString(4, note.usefulSteps());
            statement.setString(5, note.pitfalls());
            statement.setString(6, note.sourceSessionId());
            statement.setString(7, note.sourceTaskId());
            statement.setLong(8, note.updatedAtMillis());
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("保存资源笔记失败", ex);
        }
    }

    @Override
    public synchronized void save(TaskView task) {
        String sql = "MERGE INTO investigation (task_id, session_id, resource_key, status, created_at, snapshot) "
                + "KEY (task_id) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, task.taskId());
            statement.setString(2, task.sessionId());
            statement.setString(3, task.target() == null ? null : task.target().value());
            statement.setString(4, task.status().name());
            statement.setLong(5, task.createdAtMillis());
            statement.setString(6, JSON.writeValueAsString(task));
            statement.executeUpdate();
        } catch (Exception ex) {
            throw new IllegalStateException("保存调查快照失败", ex);
        }
    }

    @Override
    public synchronized Optional<TaskView> find(String taskId) {
        return query("SELECT snapshot FROM investigation WHERE task_id = ?", taskId).stream().findFirst();
    }

    @Override
    public synchronized Optional<TaskView> findActiveBySessionId(String sessionId) {
        return findBySessionId(sessionId).stream().filter(view -> view.status().active()).findFirst();
    }

    @Override
    public synchronized List<TaskView> listAll() {
        return query("SELECT snapshot FROM investigation ORDER BY created_at");
    }

    @Override
    public synchronized List<TaskView> findBySessionId(String sessionId) {
        return query("SELECT snapshot FROM investigation WHERE session_id = ? ORDER BY created_at", sessionId);
    }

    @Override
    public synchronized List<TaskView> findByIncidentId(String incidentId) {
        return listAll().stream().filter(view -> incidentId != null && incidentId.equals(view.incidentId())).toList();
    }

    @Override
    public synchronized List<TaskView> recentBySession(String sessionId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return findBySessionId(sessionId).stream()
                .sorted(Comparator.comparingLong(TaskView::createdAtMillis).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized void remove(String taskId) {
        update("DELETE FROM investigation WHERE task_id = ?", taskId);
    }

    @Override
    public synchronized int removeBySession(String sessionId) {
        return update("DELETE FROM investigation WHERE session_id = ?", sessionId);
    }

    @Override
    public synchronized int removeByIncident(String incidentId) {
        int removed = 0;
        for (TaskView view : findByIncidentId(incidentId)) {
            remove(view.taskId());
            removed++;
        }
        return removed;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ex) {
            log.warn("关闭调查记录库失败", ex);
        }
    }

    private void migrate() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS investigation ("
                    + "task_id VARCHAR(64) PRIMARY KEY, "
                    + "session_id VARCHAR(64) NOT NULL, "
                    + "resource_key VARCHAR(255), "
                    + "status VARCHAR(32) NOT NULL, "
                    + "created_at BIGINT NOT NULL, "
                    + "snapshot CLOB NOT NULL)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_investigation_session "
                    + "ON investigation(session_id, created_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS resource_note ("
                    + "resource_key VARCHAR(255) PRIMARY KEY, "
                    + "symptom VARCHAR(500), "
                    + "root_cause VARCHAR(500), "
                    + "useful_steps VARCHAR(1000), "
                    + "pitfalls VARCHAR(1000), "
                    + "source_session_id VARCHAR(64), "
                    + "source_task_id VARCHAR(64), "
                    + "updated_at BIGINT NOT NULL)");
        }
    }

    private void interruptLeftovers() {
        for (TaskView view : listAll()) {
            if (view.status() == TaskStatus.PENDING || view.status() == TaskStatus.RUNNING) {
                save(view.interrupted(INTERRUPTED));
            }
        }
    }

    private List<TaskView> query(String sql, String... args) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setString(i + 1, args[i]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                List<TaskView> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(JSON.readValue(rows.getString("snapshot"), TaskView.class));
                }
                return List.copyOf(found);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("读取调查快照失败", ex);
        }
    }

    private int update(String sql, String id) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            return statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("删除调查快照失败", ex);
        }
    }
}
