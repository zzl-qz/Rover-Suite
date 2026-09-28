package com.rover.agent.runtime.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.journal.ResourceNote;
import com.rover.agent.core.model.AgentMessage;
import com.rover.agent.core.model.AgentStepType;
import com.rover.agent.core.model.Confidence;
import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.EvidenceType;
import com.rover.agent.core.model.Hypothesis;
import com.rover.agent.core.model.Incident;
import com.rover.agent.core.model.IncidentOrigin;
import com.rover.agent.core.model.IncidentStatus;
import com.rover.agent.core.model.InvestigationReport;
import com.rover.agent.core.model.MessageRole;
import com.rover.agent.core.model.RecallChoice;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.SessionStatus;
import com.rover.agent.core.model.Step;
import com.rover.agent.core.model.StepStatus;
import com.rover.agent.core.model.TargetType;
import com.rover.agent.core.model.TaskStatus;
import com.rover.agent.core.model.TaskType;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.model.TimeRange;
import com.rover.agent.core.planning.InvestigationPlan;
import com.rover.agent.core.repository.AgentMessageRepository;
import com.rover.agent.core.repository.AgentSessionRepository;
import com.rover.agent.core.repository.AgentTaskRepository;
import com.rover.agent.core.repository.IncidentRepository;
import java.io.File;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 聚合链的一条 JDBC 持久化通道：会话 → 事件 → 任务 → 步骤 / 证据。
 *
 * <p><b>关系表才是真相，{@link TaskView} 只是投影。</b>上层继续调用
 * {@link AgentSessionRepository} / {@link IncidentRepository} / {@link AgentTaskRepository}，
 * 读写口完全没有变化；JDBC 实现内部把一次任务快照拆成 {@code agent_task} + {@code agent_step} +
 * {@code agent_evidence} 三张表，读的时候再组装回 {@link TaskView}。旧版把整颗 TaskView 序列化成
 * 一个 CLOB 的做法已经退役：局部结构（调查计划、证据的统计口径、报告的适用边界与假设）仍然用 JSON，
 * 但"查得动"的事实（状态、会话、事件、目标、时间、置信度）都是列。
 *
 * <p><b>一次快照一次事务。</b>任务状态、步骤与证据在同一个事务里落下，因此不会出现
 * "步骤已完成、证据没写进去"这种重启后才暴露的坏状态；失败整组回滚。
 *
 * <p><b>引用是外键。</b>会话指向事件、事件指向任务、任务指向步骤与证据都由外键约束保证，
 * 删会话会连带清掉它的事件、消息与任务。因此"会话指向一个已经消失的事件"不再可能发生。
 *
 * <p><b>会话上的事件列表与事件上的任务列表是查出来的。</b>它们不再作为冗余列表存两份，
 * 而是从 {@code agent_incident.session_id} / {@code agent_task.incident_id} 派生，
 * 不可能与事实不一致。
 *
 * <p>SQL 只用 H2 与 MySQL 都认的写法（{@code CREATE TABLE IF NOT EXISTS}、{@code TEXT}、
 * {@code BIGINT AUTO_INCREMENT}、{@code UPDATE} 未命中再 {@code INSERT}），
 * 不用 {@code MERGE INTO ... KEY} 之类的方言语法，也不写两套业务 SQL——换数据源只换连接来源。
 */
public final class JdbcAgentStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JdbcAgentStore.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final String INTERRUPTED = "Admin 重启，调查已中断";
    private static final TypeReference<List<Hypothesis>> HYPOTHESES = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> METADATA = new TypeReference<>() { };
    private static final TypeReference<List<AgentCapability>> CAPABILITIES = new TypeReference<>() { };
    private static final TypeReference<List<RecallChoice>> RECALLS = new TypeReference<>() { };

    private final Connection connection;
    private final Sessions sessions = new Sessions();
    private final Messages messages = new Messages();
    private final Incidents incidents = new Incidents();
    private final Tasks tasks = new Tasks();

    public JdbcAgentStore(String dbPath) {
        if (dbPath == null || dbPath.isBlank()) {
            throw new IllegalArgumentException("Agent 库路径不能为空");
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
            throw new IllegalStateException("打开 Agent 库失败: " + jdbcUrl, ex);
        }
        log.info("Agent 会话与调查记录已就绪: {}.mv.db（schema v{}）", file.getAbsolutePath(), schemaVersion());
    }

    public AgentSessionRepository sessions() {
        return sessions;
    }

    public AgentMessageRepository messages() {
        return messages;
    }

    public IncidentRepository incidents() {
        return incidents;
    }

    public AgentTaskRepository tasks() {
        return tasks;
    }

    /** 资源笔记：有已确认根因的调查才会写，按资源键读一条。 */
    public synchronized Optional<ResourceNote> findNote(String resourceKey) {
        if (resourceKey == null || resourceKey.isBlank()) {
            return Optional.empty();
        }
        return queryOne("SELECT * FROM resource_note WHERE resource_key = ?", rows -> new ResourceNote(
                rows.getString("resource_key"), rows.getString("symptom"), rows.getString("root_cause"),
                rows.getString("useful_steps"), rows.getString("pitfalls"), rows.getString("source_session_id"),
                rows.getString("source_task_id"), rows.getLong("updated_at")), resourceKey);
    }

    public synchronized void saveNote(ResourceNote note) {
        Object[] values = {note.symptom(), note.rootCause(), note.usefulSteps(), note.pitfalls(),
                note.sourceSessionId(), note.sourceTaskId(), note.updatedAtMillis(), note.resourceKey()};
        upsert("UPDATE resource_note SET symptom = ?, root_cause = ?, useful_steps = ?, pitfalls = ?, "
                        + "source_session_id = ?, source_task_id = ?, updated_at = ? WHERE resource_key = ?",
                "INSERT INTO resource_note (symptom, root_cause, useful_steps, pitfalls, source_session_id, "
                        + "source_task_id, updated_at, resource_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                values, values);
    }

    /** 当前 schema 版本；迁移只前进不回退，启动日志与诊断都看它。 */
    public synchronized int schemaVersion() {
        try {
            return currentVersion();
        } catch (SQLException ex) {
            throw new IllegalStateException("读取 schema 版本失败", ex);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ex) {
            log.warn("关闭 Agent 库失败", ex);
        }
    }

    // ---------------------------------------------------------------- 会话

    private final class Sessions implements AgentSessionRepository {

        @Override
        public void save(Session session) {
            synchronized (JdbcAgentStore.this) {
                Object[] values = {blankToNull(session.userId()), session.title(),
                        blankToNull(session.activeIncidentId()), session.status().name(),
                        session.createdAtMillis(), session.lastActiveAtMillis(), session.sessionId()};
                // 会话上的事件列表不落库：它由 agent_incident.session_id 派生，存两份只会有一天对不上。
                upsert("UPDATE agent_session SET user_id = ?, title = ?, active_incident_id = ?, status = ?, "
                                + "created_at = ?, last_active_at = ? WHERE session_id = ?",
                        "INSERT INTO agent_session (user_id, title, active_incident_id, status, created_at, "
                                + "last_active_at, session_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        values, values);
            }
        }

        @Override
        public Optional<Session> find(String sessionId) {
            synchronized (JdbcAgentStore.this) {
                return queryOne("SELECT * FROM agent_session WHERE session_id = ?", JdbcAgentStore::readSessionRow,
                        sessionId).map(JdbcAgentStore.this::assembleSession);
            }
        }

        @Override
        public List<Session> findByUserId(String userId) {
            synchronized (JdbcAgentStore.this) {
                List<SessionRow> rows = userId == null || userId.isBlank()
                        ? query("SELECT * FROM agent_session WHERE user_id IS NULL ORDER BY created_at, session_id",
                                JdbcAgentStore::readSessionRow)
                        : query("SELECT * FROM agent_session WHERE user_id = ? ORDER BY created_at, session_id",
                                JdbcAgentStore::readSessionRow, userId);
                return rows.stream().map(JdbcAgentStore.this::assembleSession).toList();
            }
        }

        @Override
        public List<Session> listAll() {
            synchronized (JdbcAgentStore.this) {
                return query("SELECT * FROM agent_session ORDER BY created_at, session_id",
                        JdbcAgentStore::readSessionRow).stream()
                        .map(JdbcAgentStore.this::assembleSession).toList();
            }
        }

        @Override
        public void remove(String sessionId) {
            synchronized (JdbcAgentStore.this) {
                // 外键级联：会话下的事件、消息与任务一并清掉，不留孤儿。
                run("DELETE FROM agent_session WHERE session_id = ?", sessionId);
            }
        }
    }

    private Session assembleSession(SessionRow row) {
        List<String> incidentIds = query("SELECT incident_id FROM agent_incident WHERE session_id = ? "
                + "ORDER BY created_at, incident_id", result -> result.getString(1), row.sessionId());
        return new Session(row.sessionId(), row.userId(), row.title(), row.activeIncidentId(), row.status(),
                row.createdAtMillis(), row.lastActiveAtMillis(), incidentIds);
    }

    private static SessionRow readSessionRow(ResultSet rows) throws SQLException {
        return new SessionRow(rows.getString("session_id"), blankToNull(rows.getString("user_id")),
                rows.getString("title"), blankToNull(rows.getString("active_incident_id")),
                SessionStatus.valueOf(rows.getString("status")), rows.getLong("created_at"),
                rows.getLong("last_active_at"));
    }

    // ---------------------------------------------------------------- 消息

    private final class Messages implements AgentMessageRepository {

        @Override
        public void save(AgentMessage message) {
            synchronized (JdbcAgentStore.this) {
                Object[] values = {message.sessionId(), message.role().name(), message.content(),
                        blankToNull(message.relatedTaskId()), message.createdAtMillis(), message.messageId()};
                // 自增 seq 只在首次落库时分配，之后重写同一条消息不会改变它在会话里的位置。
                upsert("UPDATE agent_message SET session_id = ?, role = ?, content = ?, related_task_id = ?, "
                                + "created_at = ? WHERE message_id = ?",
                        "INSERT INTO agent_message (session_id, role, content, related_task_id, created_at, "
                                + "message_id) VALUES (?, ?, ?, ?, ?, ?)",
                        values, values);
            }
        }

        @Override
        public List<AgentMessage> bySession(String sessionId) {
            synchronized (JdbcAgentStore.this) {
                return query("SELECT * FROM agent_message WHERE session_id = ? ORDER BY seq",
                        JdbcAgentStore::readMessage, sessionId);
            }
        }

        @Override
        public List<AgentMessage> listAll() {
            synchronized (JdbcAgentStore.this) {
                return query("SELECT * FROM agent_message ORDER BY seq", JdbcAgentStore::readMessage);
            }
        }

        @Override
        public Optional<AgentMessage> oldest() {
            synchronized (JdbcAgentStore.this) {
                return queryOne("SELECT * FROM agent_message ORDER BY seq LIMIT 1", JdbcAgentStore::readMessage);
            }
        }

        @Override
        public void remove(String messageId) {
            synchronized (JdbcAgentStore.this) {
                run("DELETE FROM agent_message WHERE message_id = ?", messageId);
            }
        }

        @Override
        public int removeBySession(String sessionId) {
            synchronized (JdbcAgentStore.this) {
                return run("DELETE FROM agent_message WHERE session_id = ?", sessionId);
            }
        }
    }

    private static AgentMessage readMessage(ResultSet rows) throws SQLException {
        return new AgentMessage(rows.getString("message_id"), rows.getString("session_id"),
                MessageRole.valueOf(rows.getString("role")), rows.getString("content"),
                rows.getLong("created_at"), blankToNull(rows.getString("related_task_id")));
    }

    // ---------------------------------------------------------------- 事件

    private final class Incidents implements IncidentRepository {

        @Override
        public void save(Incident incident) {
            synchronized (JdbcAgentStore.this) {
                ResourceTarget target = targetOf(incident.target());
                Object[] values = {incident.sessionId(), incident.origin().name(), incident.status().name(),
                        incident.title(), incident.summary(), target.type().name(), target.value(),
                        incident.timeRange().fromMillis(), incident.timeRange().toMillis(),
                        incident.createdAtMillis(), System.currentTimeMillis(), incident.incidentId()};
                upsert("UPDATE agent_incident SET session_id = ?, origin = ?, status = ?, title = ?, summary = ?, "
                                + "target_type = ?, target_key = ?, time_from = ?, time_to = ?, created_at = ?, "
                                + "updated_at = ?, version = version + 1 WHERE incident_id = ?",
                        "INSERT INTO agent_incident (session_id, origin, status, title, summary, target_type, "
                                + "target_key, time_from, time_to, created_at, updated_at, version, incident_id) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)",
                        values, values);
            }
        }

        @Override
        public Optional<Incident> find(String incidentId) {
            synchronized (JdbcAgentStore.this) {
                return queryOne("SELECT * FROM agent_incident WHERE incident_id = ?",
                        JdbcAgentStore::readIncidentRow, incidentId).map(JdbcAgentStore.this::assembleIncident);
            }
        }

        @Override
        public List<Incident> listAll() {
            synchronized (JdbcAgentStore.this) {
                return query("SELECT * FROM agent_incident ORDER BY created_at, incident_id",
                        JdbcAgentStore::readIncidentRow).stream()
                        .map(JdbcAgentStore.this::assembleIncident).toList();
            }
        }

        @Override
        public List<Incident> bySession(String sessionId) {
            synchronized (JdbcAgentStore.this) {
                return query("SELECT * FROM agent_incident WHERE session_id = ? ORDER BY created_at, incident_id",
                        JdbcAgentStore::readIncidentRow, sessionId).stream()
                        .map(JdbcAgentStore.this::assembleIncident).toList();
            }
        }

        @Override
        public void remove(String incidentId) {
            synchronized (JdbcAgentStore.this) {
                // 外键：指向它的事件指针被置空；它挂着的任务保留，但不再指向任何一个事件。
                run("DELETE FROM agent_incident WHERE incident_id = ?", incidentId);
            }
        }

        @Override
        public int removeBySession(String sessionId) {
            synchronized (JdbcAgentStore.this) {
                return run("DELETE FROM agent_incident WHERE session_id = ?", sessionId);
            }
        }
    }

    private Incident assembleIncident(IncidentRow row) {
        List<String> taskIds = query("SELECT task_id FROM agent_task WHERE incident_id = ? ORDER BY created_at, "
                + "task_id", result -> result.getString(1), row.incidentId());
        return new Incident(row.incidentId(), row.sessionId(), row.origin(), row.title(), row.status(),
                row.target(), row.timeRange(), row.summary(), row.createdAtMillis(), row.updatedAtMillis(), taskIds);
    }

    private static IncidentRow readIncidentRow(ResultSet rows) throws SQLException {
        // 未指定时间范围落库时是两列 NULL：读回来仍是 TimeRange.unspecified()，语义与运行时一致。
        boolean unspecified = rows.getObject("time_from") == null;
        TimeRange timeRange = unspecified ? TimeRange.unspecified()
                : new TimeRange(rows.getLong("time_from"), rows.getLong("time_to"));
        return new IncidentRow(rows.getString("incident_id"), rows.getString("session_id"),
                IncidentOrigin.valueOf(rows.getString("origin")), IncidentStatus.valueOf(rows.getString("status")),
                rows.getString("title"), nullToEmpty(rows.getString("summary")), readTarget(rows), timeRange,
                rows.getLong("created_at"), rows.getLong("updated_at"));
    }

    // ---------------------------------------------------------------- 任务

    private final class Tasks implements AgentTaskRepository {

        @Override
        public void save(TaskView task) {
            synchronized (JdbcAgentStore.this) {
                inTransaction(() -> {
                    writeTask(task);
                    writeSteps(task);
                    writeEvidence(task);
                    return null;
                });
            }
        }

        @Override
        public Optional<TaskView> find(String taskId) {
            synchronized (JdbcAgentStore.this) {
                return queryOne("SELECT * FROM agent_task WHERE task_id = ?", JdbcAgentStore::readTaskRow, taskId)
                        .map(JdbcAgentStore.this::assembleTask);
            }
        }

        @Override
        public Optional<TaskView> findActiveBySessionId(String sessionId) {
            synchronized (JdbcAgentStore.this) {
                // 与 TaskStatus.active() 同一口径：WAITING_INPUT 不算执行中。
                return queryOne("SELECT * FROM agent_task WHERE session_id = ? AND status IN (?, ?) "
                                + "ORDER BY created_at, task_id LIMIT 1", JdbcAgentStore::readTaskRow, sessionId,
                        TaskStatus.PENDING.name(), TaskStatus.RUNNING.name()).map(JdbcAgentStore.this::assembleTask);
            }
        }

        @Override
        public List<TaskView> listAll() {
            synchronized (JdbcAgentStore.this) {
                return assembleAll(query("SELECT * FROM agent_task ORDER BY created_at, task_id",
                        JdbcAgentStore::readTaskRow));
            }
        }

        @Override
        public List<TaskView> findBySessionId(String sessionId) {
            synchronized (JdbcAgentStore.this) {
                return assembleAll(query("SELECT * FROM agent_task WHERE session_id = ? ORDER BY created_at, task_id",
                        JdbcAgentStore::readTaskRow, sessionId));
            }
        }

        @Override
        public List<TaskView> findByIncidentId(String incidentId) {
            synchronized (JdbcAgentStore.this) {
                if (incidentId == null || incidentId.isBlank()) {
                    return List.of();
                }
                return assembleAll(query("SELECT * FROM agent_task WHERE incident_id = ? ORDER BY created_at, task_id",
                        JdbcAgentStore::readTaskRow, incidentId));
            }
        }

        @Override
        public List<TaskView> recentBySession(String sessionId, int limit) {
            synchronized (JdbcAgentStore.this) {
                if (limit <= 0) {
                    return List.of();
                }
                return assembleAll(query("SELECT * FROM agent_task WHERE session_id = ? "
                        + "ORDER BY created_at DESC, task_id DESC LIMIT " + limit, JdbcAgentStore::readTaskRow,
                        sessionId));
            }
        }

        @Override
        public void remove(String taskId) {
            synchronized (JdbcAgentStore.this) {
                // 外键级联：步骤与证据随任务一起删。
                run("DELETE FROM agent_task WHERE task_id = ?", taskId);
            }
        }

        @Override
        public int removeBySession(String sessionId) {
            synchronized (JdbcAgentStore.this) {
                return run("DELETE FROM agent_task WHERE session_id = ?", sessionId);
            }
        }

        @Override
        public int removeByIncident(String incidentId) {
            synchronized (JdbcAgentStore.this) {
                return run("DELETE FROM agent_task WHERE incident_id = ?", incidentId);
            }
        }
    }

    private List<TaskView> assembleAll(List<TaskRow> rows) {
        return rows.stream().map(this::assembleTask).toList();
    }

    private TaskView assembleTask(TaskRow row) {
        List<Step> steps = query("SELECT * FROM agent_step WHERE task_id = ? ORDER BY sequence_no, step_id",
                JdbcAgentStore::readStep, row.taskId());
        return new TaskView(row.taskId(), row.sessionId(), row.incidentId(), row.status(), row.currentStage(),
                nullToEmpty(row.path()), row.target(), row.question(), row.createdAtMillis(), row.completedAtMillis(),
                steps, assembleReport(row), row.error(), row.clarification(), row.taskType(),
                fromJson(row.planJson(), InvestigationPlan.class), orEmpty(fromJson(row.executedJson(),
                        CAPABILITIES)), orEmpty(fromJson(row.recallsJson(), RECALLS)));
    }

    /**
     * 报告只要有一项内容就还原；结论、置信度、解读与报告的局部结构全空时为 {@code null}，与运行时一致。
     *
     * 证据只在报告存在时读取：它本来就只随结论一起产出，任务跑到一半没有报告时也没有证据可还原。
     */
    private InvestigationReport assembleReport(TaskRow row) {
        if (row.resultSummary() == null && row.resultConfidence() == null && row.resultAiAnalysis() == null
                && row.resultLimitationsJson() == null && row.resultHypothesesJson() == null) {
            return null;
        }
        List<Evidence> evidence = query("SELECT * FROM agent_evidence WHERE task_id = ? "
                + "ORDER BY sequence_no, evidence_id", JdbcAgentStore::readEvidence, row.taskId());
        Confidence confidence = row.resultConfidence() == null ? null
                : Confidence.valueOf(row.resultConfidence());
        return new InvestigationReport(row.resultSummary(), confidence, evidence,
                orEmpty(fromJson(row.resultLimitationsJson(), STRINGS)),
                orEmpty(fromJson(row.resultHypothesesJson(), HYPOTHESES)), row.resultAiAnalysis());
    }

    private void writeTask(TaskView task) {
        InvestigationReport report = task.result();
        ResourceTarget target = targetOf(task.target());
        Object[] values = {
                task.sessionId(), blankToNull(task.incidentId()), task.taskType().name(), task.status().name(),
                task.question(), blankToNull(task.path()), target.type().name(), target.value(),
                task.currentStage() == null ? null : task.currentStage().name(), task.clarification(),
                task.error(), toJson(task.plan()), toJson(task.executedCapabilities()), toJson(task.recalls()),
                report == null ? null : report.summary(), report == null || report.confidence() == null ? null
                        : report.confidence().name(), report == null ? null : report.aiAnalysis(),
                report == null ? null : toJson(report.limitations()),
                report == null ? null : toJson(report.hypotheses()),
                task.createdAtMillis(), task.completedAtMillis(), System.currentTimeMillis(), task.taskId()};
        upsert("UPDATE agent_task SET session_id = ?, incident_id = ?, task_type = ?, status = ?, question = ?, "
                        + "path = ?, target_type = ?, target_key = ?, current_stage = ?, clarification = ?, "
                        + "error_message = ?, plan_json = ?, executed_json = ?, recalls_json = ?, result_summary = ?, "
                        + "result_confidence = ?, result_ai_analysis = ?, result_limitations_json = ?, "
                        + "result_hypotheses_json = ?, created_at = ?, completed_at = ?, updated_at = ?, "
                        + "version = version + 1 WHERE task_id = ?",
                "INSERT INTO agent_task (session_id, incident_id, task_type, status, question, path, target_type, "
                        + "target_key, current_stage, clarification, error_message, plan_json, executed_json, "
                        + "recalls_json, result_summary, result_confidence, result_ai_analysis, "
                        + "result_limitations_json, result_hypotheses_json, created_at, completed_at, updated_at, "
                        + "version, task_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "1, ?)",
                values, values);
    }

    private void writeSteps(TaskView task) {
        List<Step> steps = task.steps() == null ? List.of() : task.steps();
        List<String> kept = new ArrayList<>();
        int sequence = 0;
        for (Step step : steps) {
            int order = sequence++;
            kept.add(step.stepId());
            Object[] values = {task.taskId(), step.type().name(), order, step.status().name(), step.name(),
                    step.inputSummary(), step.outputSummary(), step.error(), step.startedAtMillis(),
                    step.finishedAtMillis(), step.stepId()};
            // 步骤会被就地改状态（同一个 stepId 从 RUNNING 变 COMPLETED），因此按 ID 更新而不是删了重插。
            upsert("UPDATE agent_step SET task_id = ?, step_type = ?, sequence_no = ?, status = ?, title = ?, "
                            + "input_summary = ?, output_summary = ?, error_message = ?, started_at = ?, "
                            + "completed_at = ? WHERE step_id = ?",
                    "INSERT INTO agent_step (task_id, step_type, sequence_no, status, title, input_summary, "
                            + "output_summary, error_message, started_at, completed_at, step_id) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    values, values);
        }
        prune("agent_step", "step_id", task.taskId(), kept);
    }

    private void writeEvidence(TaskView task) {
        InvestigationReport report = task.result();
        List<Evidence> evidence = report == null || report.evidence() == null ? List.of() : report.evidence();
        List<String> kept = new ArrayList<>();
        int sequence = 0;
        for (Evidence item : evidence) {
            int order = sequence++;
            kept.add(item.evidenceId());
            Object[] values = {task.taskId(), item.type().name(), order, item.source(), item.title(), item.summary(),
                    item.rawReference(), toJson(item.metadata()), item.observedAtMillis()};
            upsert("UPDATE agent_evidence SET task_id = ?, evidence_type = ?, sequence_no = ?, source = ?, title = ?, "
                            + "summary = ?, raw_reference = ?, payload_json = ?, observed_at = ? WHERE evidence_id = ?",
                    "INSERT INTO agent_evidence (task_id, evidence_type, sequence_no, source, title, summary, "
                            + "raw_reference, payload_json, observed_at, created_at, evidence_id) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    append(values, item.evidenceId()),
                    withCreatedAt(values, item.evidenceId()));
        }
        prune("agent_evidence", "evidence_id", task.taskId(), kept);
    }

    /** 删掉本次快照里已经不在任务上的子记录；空快照直接清空该任务的子表。 */
    private void prune(String table, String idColumn, String taskId, List<String> kept) {
        if (kept.isEmpty()) {
            run("DELETE FROM " + table + " WHERE task_id = ?", taskId);
            return;
        }
        List<Object> args = new ArrayList<>();
        args.add(taskId);
        args.addAll(kept);
        run("DELETE FROM " + table + " WHERE task_id = ? AND " + idColumn + " NOT IN ("
                + String.join(", ", kept.stream().map(id -> "?").toList()) + ")", args.toArray());
    }

    private static TaskRow readTaskRow(ResultSet rows) throws SQLException {
        return new TaskRow(rows.getString("task_id"), rows.getString("session_id"),
                blankToNull(rows.getString("incident_id")), TaskType.valueOf(rows.getString("task_type")),
                TaskStatus.valueOf(rows.getString("status")), rows.getString("question"), rows.getString("path"),
                readTarget(rows), stepType(rows.getString("current_stage")), rows.getString("clarification"),
                rows.getString("error_message"), rows.getString("plan_json"), rows.getString("executed_json"),
                rows.getString("recalls_json"), rows.getString("result_summary"),
                rows.getString("result_confidence"), rows.getString("result_ai_analysis"),
                rows.getString("result_limitations_json"), rows.getString("result_hypotheses_json"),
                rows.getLong("created_at"), rows.getLong("completed_at"));
    }

    private static Step readStep(ResultSet rows) throws SQLException {
        return new Step(rows.getString("step_id"), rows.getString("task_id"),
                AgentStepType.valueOf(rows.getString("step_type")), rows.getString("title"),
                StepStatus.valueOf(rows.getString("status")), rows.getString("input_summary"),
                rows.getString("output_summary"), rows.getLong("started_at"), rows.getLong("completed_at"),
                rows.getString("error_message"));
    }

    private static Evidence readEvidence(ResultSet rows) throws SQLException {
        return new Evidence(rows.getString("evidence_id"), rows.getString("task_id"),
                EvidenceType.valueOf(rows.getString("evidence_type")), rows.getString("source"),
                rows.getString("title"), rows.getString("summary"), rows.getString("raw_reference"),
                metadataOf(rows.getString("payload_json")), rows.getLong("observed_at"));
    }

    // ---------------------------------------------------------------- 行投影

    private record SessionRow(String sessionId, String userId, String title, String activeIncidentId,
                              SessionStatus status, long createdAtMillis, long lastActiveAtMillis) { }

    private record IncidentRow(String incidentId, String sessionId, IncidentOrigin origin, IncidentStatus status,
                               String title, String summary, ResourceTarget target, TimeRange timeRange,
                               long createdAtMillis, long updatedAtMillis) { }

    private record TaskRow(String taskId, String sessionId, String incidentId, TaskType taskType, TaskStatus status,
                           String question, String path, ResourceTarget target, AgentStepType currentStage,
                           String clarification, String error, String planJson, String executedJson,
                           String recallsJson, String resultSummary, String resultConfidence, String resultAiAnalysis,
                           String resultLimitationsJson, String resultHypothesesJson, long createdAtMillis,
                           long completedAtMillis) { }

    // ---------------------------------------------------------------- 迁移

    /** 一条外键：约束名 + 所属表 + 建约束语句（表要先建好，因此不能写在 CREATE TABLE 里）。 */
    private record ForeignKey(String name, String table, String sql) { }

    private record Migration(int version, String description, List<String> statements,
                             List<ForeignKey> foreignKeys) { }

    /**
     * 版本化迁移：只前进、只新增，已发布过的版本不再改动。
     *
     * <p>这是 Agent 库 Schema v1 的起点。v2 清掉旧 JSON 快照表——开发阶段不保留旧数据，
     * 免得"整颗 TaskView 的 CLOB"被再次当成真相来源。
     *
     * <p>索引与外键在应用前先查元数据，已存在就跳过：MySQL 的 DDL 不能回滚，
     * 万一某条迁移只执行到一半，重跑必须还能通过。索引同理不用 {@code CREATE INDEX IF NOT EXISTS}
     * （MySQL 不支持该写法）。
     */
    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "agent aggregate v1: session / message / incident / task / step / evidence",
                    List.of(
                            "CREATE TABLE IF NOT EXISTS agent_session ("
                                    + "session_id VARCHAR(64) PRIMARY KEY, "
                                    + "user_id VARCHAR(128), "
                                    + "title VARCHAR(255), "
                                    + "active_incident_id VARCHAR(64), "
                                    + "status VARCHAR(32) NOT NULL, "
                                    + "created_at BIGINT NOT NULL, "
                                    + "last_active_at BIGINT NOT NULL)",
                            "CREATE TABLE IF NOT EXISTS agent_message ("
                                    + "seq BIGINT AUTO_INCREMENT PRIMARY KEY, "
                                    + "message_id VARCHAR(64) NOT NULL, "
                                    + "session_id VARCHAR(64) NOT NULL, "
                                    + "role VARCHAR(32) NOT NULL, "
                                    + "content TEXT NOT NULL, "
                                    + "related_task_id VARCHAR(64), "
                                    + "created_at BIGINT NOT NULL)",
                            "CREATE TABLE IF NOT EXISTS agent_incident ("
                                    + "incident_id VARCHAR(64) PRIMARY KEY, "
                                    + "session_id VARCHAR(64) NOT NULL, "
                                    + "origin VARCHAR(32) NOT NULL, "
                                    + "status VARCHAR(32) NOT NULL, "
                                    + "title VARCHAR(255), "
                                    + "summary TEXT, "
                                    + "target_type VARCHAR(32), "
                                    + "target_key VARCHAR(255), "
                                    + "time_from BIGINT, "
                                    + "time_to BIGINT, "
                                    + "created_at BIGINT NOT NULL, "
                                    + "updated_at BIGINT NOT NULL, "
                                    + "version BIGINT NOT NULL)",
                            "CREATE TABLE IF NOT EXISTS agent_task ("
                                    + "task_id VARCHAR(64) PRIMARY KEY, "
                                    + "session_id VARCHAR(64) NOT NULL, "
                                    + "incident_id VARCHAR(64), "
                                    + "task_type VARCHAR(32) NOT NULL, "
                                    + "status VARCHAR(32) NOT NULL, "
                                    + "question TEXT, "
                                    + "path VARCHAR(512), "
                                    + "target_type VARCHAR(32), "
                                    + "target_key VARCHAR(255), "
                                    + "current_stage VARCHAR(48), "
                                    + "clarification TEXT, "
                                    + "error_message TEXT, "
                                    + "plan_json TEXT, "
                                    + "executed_json TEXT, "
                                    + "recalls_json TEXT, "
                                    + "result_summary TEXT, "
                                    + "result_confidence VARCHAR(32), "
                                    + "result_ai_analysis TEXT, "
                                    + "result_limitations_json TEXT, "
                                    + "result_hypotheses_json TEXT, "
                                    + "created_at BIGINT NOT NULL, "
                                    + "completed_at BIGINT NOT NULL, "
                                    + "updated_at BIGINT NOT NULL, "
                                    + "version BIGINT NOT NULL)",
                            "CREATE TABLE IF NOT EXISTS agent_step ("
                                    + "step_id VARCHAR(64) PRIMARY KEY, "
                                    + "task_id VARCHAR(64) NOT NULL, "
                                    + "step_type VARCHAR(48) NOT NULL, "
                                    + "sequence_no INT NOT NULL, "
                                    + "status VARCHAR(32) NOT NULL, "
                                    + "title VARCHAR(255), "
                                    + "input_summary TEXT, "
                                    + "output_summary TEXT, "
                                    + "error_message TEXT, "
                                    + "started_at BIGINT NOT NULL, "
                                    + "completed_at BIGINT NOT NULL)",
                            "CREATE TABLE IF NOT EXISTS agent_evidence ("
                                    + "evidence_id VARCHAR(64) PRIMARY KEY, "
                                    + "task_id VARCHAR(64) NOT NULL, "
                                    + "evidence_type VARCHAR(48) NOT NULL, "
                                    + "sequence_no INT NOT NULL, "
                                    + "source VARCHAR(255), "
                                    + "title VARCHAR(255), "
                                    + "summary TEXT, "
                                    + "raw_reference VARCHAR(1000), "
                                    + "payload_json TEXT, "
                                    + "observed_at BIGINT NOT NULL, "
                                    + "created_at BIGINT NOT NULL)",
                            // 资源笔记不是 Agent 聚合的一部分，但它和会话/任务同库同生命周期，因此一起纳入本版 schema。
                            "CREATE TABLE IF NOT EXISTS resource_note ("
                                    + "resource_key VARCHAR(255) PRIMARY KEY, "
                                    + "symptom VARCHAR(500), "
                                    + "root_cause VARCHAR(500), "
                                    + "useful_steps VARCHAR(1000), "
                                    + "pitfalls VARCHAR(1000), "
                                    + "source_session_id VARCHAR(64), "
                                    + "source_task_id VARCHAR(64), "
                                    + "updated_at BIGINT NOT NULL)"),
                    List.of(
                            new ForeignKey("fk_agent_message_session", "agent_message",
                                    "ALTER TABLE agent_message ADD CONSTRAINT fk_agent_message_session "
                                            + "FOREIGN KEY (session_id) REFERENCES agent_session(session_id) "
                                            + "ON DELETE CASCADE"),
                            new ForeignKey("fk_agent_incident_session", "agent_incident",
                                    "ALTER TABLE agent_incident ADD CONSTRAINT fk_agent_incident_session "
                                            + "FOREIGN KEY (session_id) REFERENCES agent_session(session_id) "
                                            + "ON DELETE CASCADE"),
                            new ForeignKey("fk_agent_session_active", "agent_session",
                                    "ALTER TABLE agent_session ADD CONSTRAINT fk_agent_session_active "
                                            + "FOREIGN KEY (active_incident_id) REFERENCES agent_incident(incident_id) "
                                            + "ON DELETE SET NULL"),
                            new ForeignKey("fk_agent_task_session", "agent_task",
                                    "ALTER TABLE agent_task ADD CONSTRAINT fk_agent_task_session "
                                            + "FOREIGN KEY (session_id) REFERENCES agent_session(session_id) "
                                            + "ON DELETE CASCADE"),
                            new ForeignKey("fk_agent_task_incident", "agent_task",
                                    "ALTER TABLE agent_task ADD CONSTRAINT fk_agent_task_incident "
                                            + "FOREIGN KEY (incident_id) REFERENCES agent_incident(incident_id) "
                                            + "ON DELETE SET NULL"),
                            new ForeignKey("fk_agent_step_task", "agent_step",
                                    "ALTER TABLE agent_step ADD CONSTRAINT fk_agent_step_task "
                                            + "FOREIGN KEY (task_id) REFERENCES agent_task(task_id) "
                                            + "ON DELETE CASCADE"),
                            new ForeignKey("fk_agent_evidence_task", "agent_evidence",
                                    "ALTER TABLE agent_evidence ADD CONSTRAINT fk_agent_evidence_task "
                                            + "FOREIGN KEY (task_id) REFERENCES agent_task(task_id) "
                                            + "ON DELETE CASCADE"))),
            new Migration(2, "retire legacy json stores: investigation / chat_session / chat_message",
                    List.of("DROP TABLE IF EXISTS investigation",
                            "DROP TABLE IF EXISTS chat_session",
                            "DROP TABLE IF EXISTS chat_message"),
                    List.of()));

    /** v1 要建的索引：同样按元数据判断是否已存在。 */
    private static final List<ForeignKey> INDEXES = List.of(
            new ForeignKey("idx_agent_message_session", "agent_message",
                    "CREATE INDEX idx_agent_message_session ON agent_message(session_id, seq)"),
            new ForeignKey("idx_agent_incident_session", "agent_incident",
                    "CREATE INDEX idx_agent_incident_session ON agent_incident(session_id, created_at)"),
            new ForeignKey("idx_agent_task_session", "agent_task",
                    "CREATE INDEX idx_agent_task_session ON agent_task(session_id, created_at)"),
            new ForeignKey("idx_agent_task_incident", "agent_task",
                    "CREATE INDEX idx_agent_task_incident ON agent_task(incident_id)"),
            new ForeignKey("idx_agent_task_status", "agent_task",
                    "CREATE INDEX idx_agent_task_status ON agent_task(status)"),
            new ForeignKey("idx_agent_step_task", "agent_step",
                    "CREATE INDEX idx_agent_step_task ON agent_step(task_id, sequence_no)"),
            new ForeignKey("idx_agent_evidence_task", "agent_evidence",
                    "CREATE INDEX idx_agent_evidence_task ON agent_evidence(task_id, sequence_no)"));

    private void migrate() throws SQLException {
        // applied_at 用毫秒时间戳：与业务表同一口径，也不依赖各驱动对 TIMESTAMP 的绑定细节。
        execute("CREATE TABLE IF NOT EXISTS agent_schema_migrations ("
                + "version INT PRIMARY KEY, description VARCHAR(255), applied_at BIGINT NOT NULL)");
        int current = currentVersion();
        for (Migration migration : MIGRATIONS) {
            if (migration.version() > current) {
                applyMigration(migration);
            }
        }
    }

    private int currentVersion() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT MAX(version) FROM agent_schema_migrations")) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private void applyMigration(Migration migration) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            for (String sql : migration.statements()) {
                execute(sql);
            }
            for (ForeignKey key : migration.foreignKeys()) {
                if (!hasForeignKey(key.table(), key.name())) {
                    execute(key.sql());
                }
            }
            if (migration.version() == 1) {
                for (ForeignKey index : INDEXES) {
                    if (!hasIndex(index.name())) {
                        execute(index.sql());
                    }
                }
            }
            try (PreparedStatement record = connection.prepareStatement(
                    "INSERT INTO agent_schema_migrations (version, description, applied_at) VALUES (?, ?, ?)")) {
                record.setInt(1, migration.version());
                record.setString(2, migration.description());
                record.setLong(3, System.currentTimeMillis());
                record.executeUpdate();
            }
            connection.commit();
            log.info("Agent 库迁移已应用: v{} {}", migration.version(), migration.description());
        } catch (SQLException ex) {
            connection.rollback();
            throw new IllegalStateException("应用迁移 v" + migration.version() + " 失败: " + migration.description(),
                    ex);
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    /**
     * 约束是否已经存在。
     *
     * 表名按原样与大写各查一次：H2 把未加引号的标识符统一成大写，MySQL 则区分大小写地存库名与表名。
     * 查不到只是"还没有"，不是错误——JDBC 元数据对不存在的表返回空结果集。
     */
    private boolean hasForeignKey(String table, String name) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String expected = name.toLowerCase(Locale.ROOT);
        for (String candidate : List.of(table, table.toUpperCase(Locale.ROOT))) {
            try (ResultSet keys = metadata.getImportedKeys(null, null, candidate)) {
                while (keys.next()) {
                    String found = keys.getString("FK_NAME");
                    if (found != null && found.toLowerCase(Locale.ROOT).equals(expected)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasIndex(String name) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String expected = name.toLowerCase(Locale.ROOT);
        Set<String> found = new HashSet<>();
        for (String table : List.of("agent_message", "agent_incident", "agent_task", "agent_step", "agent_evidence")) {
            for (String candidate : List.of(table, table.toUpperCase(Locale.ROOT))) {
                try (ResultSet indexes = metadata.getIndexInfo(null, null, candidate, false, false)) {
                    while (indexes.next()) {
                        String indexName = indexes.getString("INDEX_NAME");
                        if (indexName != null) {
                            found.add(indexName.toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
        }
        return found.contains(expected);
    }

    /**
     * 重启后仍在执行的任务不能假装还在跑：它们没有线程了。
     *
     * 只动状态、错误与完成时间三列，结论与证据保持原样；停在澄清点的任务不受影响。
     */
    private void interruptLeftovers() {
        synchronized (this) {
            int interrupted = run("UPDATE agent_task SET status = ?, error_message = ?, completed_at = ?, "
                            + "updated_at = ?, version = version + 1 WHERE status IN (?, ?)",
                    TaskStatus.FAILED.name(), INTERRUPTED, System.currentTimeMillis(), System.currentTimeMillis(),
                    TaskStatus.PENDING.name(), TaskStatus.RUNNING.name());
            if (interrupted > 0) {
                log.warn("重启后中断 {} 个仍在执行的任务（PENDING / RUNNING → FAILED）", interrupted);
            }
        }
    }

    // ---------------------------------------------------------------- JDBC 基础设施

    private interface SqlWork<T> {
        T run();
    }

    private interface RowMapper<T> {
        T map(ResultSet rows) throws SQLException;
    }

    /**
     * 一组写入要么全成、要么全败。
     *
     * 任务是"状态 + 步骤 + 证据"的聚合：分开写会出现"步骤完成了、证据没落库"这种重启后才发现的坏状态。
     * 这里用显式事务而不是 Spring 的 {@code @Transactional}，运行层因此不必引入事务管理器依赖；
     * 换数据源时只换连接来源，语义不变。
     */
    private <T> T inTransaction(SqlWork<T> work) {
        try {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.run();
                connection.commit();
                return result;
            } catch (RuntimeException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Agent 库事务失败", ex);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * 先更新、未命中再插入：H2 与 MySQL 都认的 upsert 写法。
     *
     * 不用 H2 的 {@code MERGE INTO ... KEY}，也不用 MySQL 的 {@code ON DUPLICATE KEY}：
     * 前者是方言，后者会重置自增序号与创建时间，而"消息在会话里的位置"必须稳定。
     */
    private void upsert(String updateSql, String insertSql, Object[] updateArgs, Object[] insertArgs) {
        synchronized (this) {
            if (run(updateSql, updateArgs) == 0) {
                run(insertSql, insertArgs);
            }
        }
    }

    private int run(String sql, Object... args) {
        synchronized (this) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, args);
                return statement.executeUpdate();
            } catch (SQLException ex) {
                throw new IllegalStateException("写入 Agent 库失败: " + sql, ex);
            }
        }
    }

    private <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        synchronized (this) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, args);
                try (ResultSet rows = statement.executeQuery()) {
                    List<T> found = new ArrayList<>();
                    while (rows.next()) {
                        found.add(mapper.map(rows));
                    }
                    return List.copyOf(found);
                }
            } catch (SQLException ex) {
                throw new IllegalStateException("读取 Agent 库失败: " + sql, ex);
            }
        }
    }

    private <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
        return query(sql, mapper, args).stream().findFirst();
    }

    private static void bind(PreparedStatement statement, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            if (args[i] == null) {
                statement.setNull(i + 1, Types.VARCHAR);
            } else {
                statement.setObject(i + 1, args[i]);
            }
        }
    }

    private static Object[] append(Object[] values, Object last) {
        Object[] result = new Object[values.length + 1];
        System.arraycopy(values, 0, result, 0, values.length);
        result[values.length] = last;
        return result;
    }

    /** 证据的建单时间只在首次落库时写一次，重写同一条证据不会把它刷新成"刚刚"。 */
    private static Object[] withCreatedAt(Object[] values, Object last) {
        Object[] result = new Object[values.length + 2];
        System.arraycopy(values, 0, result, 0, values.length);
        result[values.length] = System.currentTimeMillis();
        result[values.length + 1] = last;
        return result;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static ResourceTarget targetOf(ResourceTarget target) {
        return target == null ? ResourceTarget.unknown() : target;
    }

    private static ResourceTarget readTarget(ResultSet rows) throws SQLException {
        String type = rows.getString("target_type");
        return type == null ? ResourceTarget.unknown()
                : new ResourceTarget(TargetType.valueOf(type), nullToEmpty(rows.getString("target_key")));
    }

    private static AgentStepType stepType(String value) {
        return value == null ? null : AgentStepType.valueOf(value);
    }

    private static <T> List<T> orEmpty(List<T> value) {
        return value == null ? List.of() : value;
    }

    /** 证据的统计口径：落库时是 JSON，读回来必须是非 null 的 Map，证据描述依赖它。 */
    private static Map<String, String> metadataOf(String json) {
        Map<String, String> metadata = fromJson(json, METADATA);
        return metadata == null ? Map.of() : metadata;
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化任务快照字段失败", ex);
        }
    }

    private static <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalStateException("解析任务快照字段失败: " + type.getSimpleName(), ex);
        }
    }

    private static <T> T fromJson(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalStateException("解析任务快照字段失败", ex);
        }
    }
}
