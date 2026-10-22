package com.rover.agent.runtime.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.journal.ResourceNote;
import com.rover.agent.core.model.ActionStatus;
import com.rover.agent.core.model.AutoExecutionPolicy;
import com.rover.agent.core.model.AlertDelivery;
import com.rover.agent.core.model.AlertEventState;
import com.rover.agent.core.model.AlertLifecycle;
import com.rover.agent.core.model.AgentAction;
import com.rover.agent.core.model.ActionApproval;
import com.rover.agent.core.model.ActionExecutionCommand;
import com.rover.agent.core.model.ActionEffectVerification;
import com.rover.agent.core.model.ActionType;
import com.rover.agent.core.model.AgentAction;
import com.rover.agent.core.model.AgentCheckpoint;
import com.rover.agent.core.model.AgentMessage;
import com.rover.agent.core.model.AgentStepType;
import com.rover.agent.core.model.CheckpointStage;
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
import com.rover.agent.core.model.RemediationPlan;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.SessionStatus;
import com.rover.agent.core.model.Step;
import com.rover.agent.core.model.StepStatus;
import com.rover.agent.core.model.TargetType;
import com.rover.agent.core.model.TaskStatus;
import com.rover.agent.core.model.WeightRequestUnit;
import com.rover.agent.core.model.TaskType;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.model.TimeRange;
import com.rover.agent.core.planning.InvestigationPlan;
import com.rover.agent.core.repository.AgentActionRepository;
import com.rover.agent.core.repository.ActionCommandRepository;
import com.rover.agent.core.repository.AutoExecutionPolicyRepository;
import com.rover.agent.core.repository.ActionEffectVerificationRepository;
import com.rover.agent.core.repository.AutonomousActionBudgetRepository;
import com.rover.agent.core.repository.AlertLifecycleRepository;
import com.rover.agent.core.repository.ActionUpdateConflictException;
import com.rover.agent.core.repository.AgentCheckpointRepository;
import com.rover.agent.core.repository.AgentMessageRepository;
import com.rover.agent.core.repository.AgentSessionRepository;
import com.rover.agent.core.repository.AgentTaskRepository;
import com.rover.agent.core.repository.IncidentRepository;
import com.rover.agent.core.util.Texts;
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
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通过 JDBC 保存会话、事件、任务、步骤和证据，TaskView 为查询投影。
 * 任务快照在同一事务内保存，关联记录由外键及级联删除约束。
 * 使用 H2 和 MySQL 兼容 SQL，局部复杂结构保存为 JSON。
 */
public final class JdbcAgentStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JdbcAgentStore.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final String INTERRUPTED = "Admin 重启，调查已中断";
    /** 重启恢复用语：提交前就断了，网关一定没收到写请求。 */
    private static final String RESTART_NOT_SUBMITTED = "Admin 重启，这条变更在提交前中断：Gateway 未被改动，可以重新发起";
    /** 重启恢复用语：幂等号已落库，写请求可能已经生效，只能按「结果未知」处理。 */
    private static final String RESTART_UNKNOWN = "Admin 重启时这条变更还在执行中：写请求可能已经生效，"
            + "请用同一操作号确认结果，不要重新执行";
    /** 重启恢复用语：回滚没发出去，权重还是变更后的值。 */
    private static final String RESTART_ROLLBACK_NOT_SUBMITTED = "Admin 重启，回滚在提交前中断："
            + "当前权重仍是变更后的值，可以重试回滚";
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
    private final Checkpoints checkpoints = new Checkpoints();
    private final Actions actions = new Actions();
    private final ActionCommands actionCommands = new ActionCommands();
    private final EffectVerifications effectVerifications = new EffectVerifications();
    private final AlertLifecycles alertLifecycles = new AlertLifecycles();
    private final AutoPolicies autoPolicies = new AutoPolicies();
    private final AutonomousBudget autonomousBudget = new AutonomousBudget();

    public JdbcAgentStore(String dbPath) {
        if (dbPath == null || dbPath.isBlank()) {
            throw new IllegalArgumentException("Agent 库路径不能为空");
        }
        File file = new File(dbPath);
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        String jdbcUrl = "jdbc:h2:file:" + dbPath + ";DB_CLOSE_DELAY=0;AUTO_SERVER=TRUE;WRITE_DELAY=0";
        try {
            connection = DriverManager.getConnection(jdbcUrl, "sa", "");
            migrate();
            recoverJobs();
            recoverActionCommands();
            recoverInterrupted();
            recoverInterruptedActions();
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

    public AgentCheckpointRepository checkpoints() {
        return checkpoints;
    }

    /** 变更记录：提议、批准、执行与回滚的全过程都落在 agent_action 一行里。 */
    public AgentActionRepository actions() {
        return actions;
    }

    public ActionCommandRepository actionCommands() { return actionCommands; }

    public ActionEffectVerificationRepository effectVerifications() { return effectVerifications; }

    public AlertLifecycleRepository alertLifecycles() { return alertLifecycles; }

    public AutoExecutionPolicyRepository autoExecutionPolicies() { return autoPolicies; }

    public AutonomousActionBudgetRepository autonomousActionBudget() { return autonomousBudget; }

    private final class AutonomousBudget implements AutonomousActionBudgetRepository {
        @Override public Reservation reserve(String key, String actionId, String reservationId, double impact, double maximum,
                long window, long cooldown, int direction, boolean budgeted, long now) {
            synchronized (JdbcAgentStore.this) {
                return inTransaction(() -> {
                    var current = queryOne("SELECT * FROM agent_action_budget WHERE resource_key=?",
                            row -> new BudgetRow(row.getString("locked_action"), row.getString("locked_reservation"), row.getLong("cooldown_until"),
                                    row.getLong("budget_window"), row.getDouble("reserved_percent"),
                                    row.getLong("reserved_cooldown"), row.getInt("reserved_direction"),
                                    row.getBoolean("reserved_budgeted")), key).orElse(null);
                    if (current == null) {
                        run("INSERT INTO agent_action_budget(resource_key,locked_action,budget_window,cooldown_until,reserved_percent,reserved_cooldown,reserved_direction,reserved_budgeted) "
                                        + "VALUES(?,NULL,?,0,0,0,0,FALSE)", key, window);
                        current = new BudgetRow(null, null, 0, window, 0, 0, 0, false);
                    }
                    if (actionId.equals(current.lockedAction()) && reservationId.equals(current.lockedReservation())) { return Reservation.ALREADY_RESERVED; }
                    if (current.lockedAction() != null) { return Reservation.RESOURCE_BUSY; }
                    long cooldownUntil = current.cooldownUntil();
                    if (budgeted && now < cooldownUntil) { return Reservation.COOLDOWN_ACTIVE; }
                    if (budgeted) {
                        long cutoff = now - window;
                        run("DELETE FROM agent_action_budget_event WHERE resource_key=? AND applied_at<=?", key, cutoff);
                        double used = queryOne("SELECT COALESCE(SUM(impact_percent),0) FROM agent_action_budget_event WHERE resource_key=? AND applied_at>?",
                                row -> row.getDouble(1), key, cutoff).orElse(0.0);
                        List<Integer> recent = query("SELECT direction FROM agent_action_budget_event WHERE resource_key=? AND applied_at>? ORDER BY event_order DESC LIMIT 2",
                                row -> row.getInt(1), key, cutoff);
                        if (recent.size() >= 2 && recent.get(0) != direction && recent.get(1) == direction) {
                            return Reservation.OSCILLATION_DETECTED;
                        }
                        if (used + impact > maximum + 1e-9) { return Reservation.BUDGET_EXCEEDED; }
                    }
                    run("UPDATE agent_action_budget SET locked_action=?,locked_reservation=?,budget_window=?,reserved_percent=?,reserved_cooldown=?,reserved_direction=?,reserved_budgeted=? WHERE resource_key=?",
                            actionId, reservationId, window, impact, cooldown, direction, budgeted, key);
                    return Reservation.RESERVED;
                });
            }
        }

        @Override public void complete(String reservationId, Outcome outcome, int direction, long now) {
            synchronized (JdbcAgentStore.this) {
                inTransaction(() -> {
                    BudgetRow row = queryOne("SELECT * FROM agent_action_budget WHERE locked_reservation=?",
                            result -> new BudgetRow(result.getString("locked_action"), result.getString("locked_reservation"), result.getLong("cooldown_until"),
                                    result.getLong("budget_window"), result.getDouble("reserved_percent"), result.getLong("reserved_cooldown"),
                                    result.getInt("reserved_direction"), result.getBoolean("reserved_budgeted")), reservationId).orElse(null);
                    if (row == null || outcome == Outcome.UNKNOWN) { return null; }
                    if (outcome == Outcome.APPLIED && direction != row.reservedDirection()) {
                        throw new IllegalStateException("autonomous budget direction binding changed");
                    }
                    long cooldownUntil = row.cooldownUntil();
                    if (outcome == Outcome.APPLIED && row.reservedBudgeted()) {
                        queryOne("SELECT resource_key FROM agent_action_budget WHERE locked_action=?",
                                result -> result.getString(1), row.lockedAction()).ifPresent(key -> run("INSERT INTO agent_action_budget_event(resource_key,action_id,reservation_id,applied_at,impact_percent,direction) VALUES(?,?,?,?,?,?)",
                                key, eventId(reservationId), eventId(reservationId), now, row.reservedPercent(), direction));
                        cooldownUntil = Math.addExact(now, row.reservedCooldown());
                    }
                    run("UPDATE agent_action_budget SET locked_action=NULL,locked_reservation=NULL,cooldown_until=?,reserved_percent=0,reserved_cooldown=0,reserved_direction=0,reserved_budgeted=FALSE WHERE locked_reservation=?",
                            cooldownUntil, reservationId);
                    return null;
                });
            }
        }
    }

    private record BudgetRow(String lockedAction, String lockedReservation, long cooldownUntil, long budgetWindow, double reservedPercent,
                             long reservedCooldown, int reservedDirection, boolean reservedBudgeted) {
    }

    private static String eventId(String actionId) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(actionId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private final class AutoPolicies implements AutoExecutionPolicyRepository {
        @Override public List<AutoExecutionPolicy> list(String namespace) {
            synchronized (JdbcAgentStore.this) {
                return query("SELECT p.payload_json FROM agent_auto_policy p JOIN (SELECT policy_id,MAX(policy_version) AS v "
                                + "FROM agent_auto_policy WHERE target_namespace=? GROUP BY policy_id) latest "
                                + "ON p.policy_id=latest.policy_id AND p.policy_version=latest.v ORDER BY p.policy_id",
                        row -> fromJson(row.getString(1), AutoExecutionPolicy.class), namespace);
            }
        }

        @Override public Optional<AutoExecutionPolicy> find(String policyId) {
            synchronized (JdbcAgentStore.this) {
                return queryOne("SELECT payload_json FROM agent_auto_policy WHERE policy_id=? ORDER BY policy_version DESC LIMIT 1",
                        row -> fromJson(row.getString(1), AutoExecutionPolicy.class), policyId);
            }
        }

        @Override public Optional<AutoExecutionPolicy> find(String policyId, String namespace) {
            synchronized (JdbcAgentStore.this) {
                return queryOne("SELECT payload_json FROM agent_auto_policy WHERE policy_id=? AND target_namespace=? "
                                + "ORDER BY policy_version DESC LIMIT 1",
                        row -> fromJson(row.getString(1), AutoExecutionPolicy.class), policyId, namespace);
            }
        }

        @Override public List<AutoExecutionPolicy> history(String policyId) {
            synchronized (JdbcAgentStore.this) {
                return query("SELECT payload_json FROM agent_auto_policy WHERE policy_id=? ORDER BY policy_version",
                        row -> fromJson(row.getString(1), AutoExecutionPolicy.class), policyId);
            }
        }

        @Override public AutoExecutionPolicy save(AutoExecutionPolicy policy, long expectedVersion) {
            synchronized (JdbcAgentStore.this) {
                return inTransaction(() -> {
                    AutoExecutionPolicy current = find(policy.policyId()).orElse(null);
                    long actual = current == null ? 0 : current.version();
                    if (actual != expectedVersion || policy.version() != expectedVersion + 1
                            || current != null && !current.targetNamespace().equals(policy.targetNamespace())) {
                        throw new IllegalStateException("preauthorization policy version conflict");
                    }
                    run("INSERT INTO agent_auto_policy(policy_id,policy_version,target_namespace,enabled,payload_json,updated_at) "
                                    + "VALUES(?,?,?,?,?,?)", policy.policyId(), policy.version(), policy.targetNamespace(),
                            policy.enabled(), toJson(policy), policy.updatedAtMillis());
                    appendAudit("AUTO_POLICY_VERSIONED", policy.policyId(), policy.updatedBy(), policy.targetNamespace(), policy);
                    return policy;
                });
            }
        }
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

    /** 独立于会话的审计记录；删除工作区不会删除历史执行证据。 */
    public record Audit(long id, String kind, String aggregateId, String actor, String namespace,
                        String payloadJson, long createdAtMillis) { }

    public synchronized void appendAudit(String kind, String aggregateId, String actor, String namespace, Object payload) {
        run("INSERT INTO agent_audit(event_kind,aggregate_id,actor,resource_namespace,payload_json,created_at) VALUES(?,?,?,?,?,?)",
                kind, aggregateId, actor, namespace, toJson(payload), System.currentTimeMillis());
    }

    public synchronized List<Audit> audit(String namespace, long after, int limit) {
        return query("SELECT * FROM agent_audit WHERE resource_namespace=? AND audit_id>? ORDER BY audit_id LIMIT ?",
                row -> new Audit(row.getLong("audit_id"), row.getString("event_kind"), row.getString("aggregate_id"),
                        row.getString("actor"), row.getString("resource_namespace"), row.getString("payload_json"), row.getLong("created_at")),
                namespace, after, Math.max(1, Math.min(1000, limit)));
    }

    /** H2 的在线一致性备份；调用者只能传服务端配置的备份路径。 */
    public synchronized void backup(java.nio.file.Path destination) {
        try {
            java.nio.file.Files.createDirectories(destination.toAbsolutePath().getParent());
            if (java.nio.file.Files.exists(destination)) { throw new IllegalArgumentException("备份目标已存在"); }
            try (PreparedStatement statement = connection.prepareStatement("BACKUP TO ?")) {
                statement.setString(1, destination.toAbsolutePath().toString());
                statement.execute();
            }
        } catch (SQLException | java.io.IOException ex) { throw new IllegalStateException("一致性备份失败", ex); }
    }

    public synchronized boolean healthy() {
        try { return connection.isValid(2) && currentVersion() == MIGRATIONS.get(MIGRATIONS.size() - 1).version(); }
        catch (SQLException ex) { return false; }
    }

    public com.rover.agent.runtime.timeline.JdbcChangeTimeline timeline() {
        return new com.rover.agent.runtime.timeline.JdbcChangeTimeline(connection, this);
    }

    public record Job(String taskId, String kind, String payloadJson, long attempt, String state,
                      String budgetJson, long notBeforeMillis) { }

    public synchronized void enqueueJob(String taskId, String kind, Object payload) {
        enqueueJob(taskId, kind, payload, 0);
    }

    public synchronized void enqueueJob(String taskId, String kind, Object payload, long notBeforeMillis) {
        if (notBeforeMillis < 0) { throw new IllegalArgumentException("唤醒时间不能为负数"); }
        run("INSERT INTO agent_job(task_id,job_kind,payload_json,attempt,state,budget_json,not_before) VALUES(?,?,?,0,'PENDING',NULL,?)",
                taskId, kind, toJson(payload), notBeforeMillis);
    }

    public synchronized List<Job> pendingJobs() {
        return query("SELECT * FROM agent_job WHERE state='PENDING' AND not_before<=? ORDER BY task_id LIMIT 200",
                JdbcAgentStore::readJob, System.currentTimeMillis());
    }

    private static Job readJob(ResultSet row) throws SQLException {
        return new Job(row.getString("task_id"), row.getString("job_kind"), row.getString("payload_json"),
                row.getLong("attempt"), row.getString("state"), row.getString("budget_json"), row.getLong("not_before"));
    }

    private static ActionCommandRepository.Job readActionCommandJob(ResultSet row) throws SQLException {
        var command = new ActionExecutionCommand(row.getString("command_id"), row.getString("action_id"),
                ActionType.valueOf(row.getString("action_type")),
                ActionExecutionCommand.Phase.valueOf(row.getString("phase")),
                fromJson(row.getString("scope_json"), com.rover.agent.core.model.ResourceScope.class),
                row.getString("control_target_id"), row.getString("target_namespace"), row.getInt("plan_version"),
                row.getString("plan_hash"), row.getString("policy_version"), row.getString("operation_id"), row.getString("actor"),
                row.getLong("created_at"));
        return new ActionCommandRepository.Job(command, row.getLong("attempt"), row.getString("state"),
                row.getLong("not_before"));
    }

    private final class AlertLifecycles implements AlertLifecycleRepository {
        @Override public <T> T transaction(java.util.function.Function<AlertLifecycleRepository.Transaction, T> work) {
            synchronized (JdbcAgentStore.this) {
                return inTransaction(() -> work.apply(new AlertLifecycleRepository.Transaction() {
                    @Override public Optional<AlertDelivery> delivery(String sourceId, String deliveryId) {
                        return queryOne("SELECT * FROM agent_alert_delivery WHERE source_id=? AND delivery_id=?",
                                row -> new AlertDelivery(row.getString("source_id"), row.getString("delivery_id"),
                                        row.getString("alert_key"), AlertEventState.valueOf(row.getString("event_state")),
                                        row.getString("payload_fingerprint"), row.getLong("generation"),
                                        row.getString("session_id"), row.getString("incident_id"), row.getString("task_id"),
                                        row.getLong("accepted_at")), sourceId, deliveryId);
                    }

                    @Override public Optional<AlertLifecycle> lifecycle(String sourceId, String alertKey) {
                        return queryOne("SELECT * FROM agent_alert_lifecycle WHERE source_id=? AND alert_key=?",
                                row -> new AlertLifecycle(row.getString("source_id"), row.getString("alert_key"),
                                        row.getBoolean("active"), row.getString("path"), row.getLong("generation"),
                                        row.getString("session_id"), row.getString("incident_id"), row.getString("task_id"),
                                        AlertEventState.valueOf(row.getString("last_state")),
                                        row.getString("last_fingerprint"), row.getLong("updated_at")), sourceId, alertKey);
                    }

                    @Override public boolean saveDelivery(AlertDelivery delivery) {
                        if (queryOne("SELECT delivery_id FROM agent_alert_delivery WHERE source_id=? AND delivery_id=?",
                                row -> row.getString(1), delivery.sourceId(), delivery.deliveryId()).isPresent()) { return false; }
                        run("INSERT INTO agent_alert_delivery(source_id,delivery_id,alert_key,event_state,"
                                        + "payload_fingerprint,generation,session_id,incident_id,task_id,accepted_at) "
                                        + "VALUES(?,?,?,?,?,?,?,?,?,?)",
                                delivery.sourceId(), delivery.deliveryId(), delivery.alertKey(), delivery.state().name(),
                                delivery.payloadFingerprint(), delivery.generation(), delivery.sessionId(),
                                delivery.incidentId(), delivery.taskId(), delivery.acceptedAtMillis());
                        return true;
                    }

                    @Override public void saveLifecycle(AlertLifecycle item) {
                        upsert("UPDATE agent_alert_lifecycle SET active=?,path=?,generation=?,session_id=?,incident_id=?,task_id=?,"
                                        + "last_state=?,last_fingerprint=?,updated_at=? WHERE source_id=? AND alert_key=?",
                                "INSERT INTO agent_alert_lifecycle(active,path,generation,session_id,incident_id,task_id,last_state,"
                                        + "last_fingerprint,updated_at,source_id,alert_key) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                                new Object[]{item.active(), item.path(), item.generation(), item.sessionId(), item.incidentId(),
                                        item.taskId(), item.lastState().name(), item.lastPayloadFingerprint(), item.updatedAtMillis(),
                                        item.sourceId(), item.alertKey()},
                                new Object[]{item.active(), item.path(), item.generation(), item.sessionId(), item.incidentId(),
                                        item.taskId(), item.lastState().name(), item.lastPayloadFingerprint(), item.updatedAtMillis(),
                                        item.sourceId(), item.alertKey()});
                    }

                    @Override public void pruneDeliveriesBefore(long cutoff) {
                        run("DELETE FROM agent_alert_delivery WHERE accepted_at<?", cutoff);
                    }
                }));
            }
        }
    }

    private final class EffectVerifications implements ActionEffectVerificationRepository {
        @Override public Optional<ActionEffectVerification> find(String actionId, ActionEffectVerification.Phase phase) {
            synchronized (JdbcAgentStore.this) {
                return queryOne("SELECT payload_json,version FROM agent_action_verification WHERE action_id=? AND phase=?",
                        row -> fromJson(row.getString(1), ActionEffectVerification.class).withVersion(row.getLong(2)),
                        actionId, phase.name());
            }
        }

        @Override public ActionEffectVerification save(ActionEffectVerification item) {
            synchronized (JdbcAgentStore.this) {
                return inTransaction(() -> {
                    int changed;
                    if (item.version() == 0) {
                        changed = run("INSERT INTO agent_action_verification(action_id,phase,plan_version,plan_hash,state,outcome,"
                                        + "baseline_at,applied_at,next_window_at,deadline_at,windows_seen,recovered_windows,payload_json,version) "
                                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,1)", item.actionId(), item.phase().name(),
                                item.planVersion(), item.planHash(), item.state().name(), item.outcome().name(),
                                item.baselineAtMillis(), item.appliedAtMillis(), item.nextWindowAtMillis(), item.deadlineAtMillis(),
                                item.windowsSeen(), item.consecutiveRecoveredWindows(), toJson(item));
                    } else {
                        changed = run("UPDATE agent_action_verification SET plan_version=?,plan_hash=?,state=?,outcome=?,"
                                        + "baseline_at=?,applied_at=?,next_window_at=?,deadline_at=?,windows_seen=?,recovered_windows=?,"
                                        + "payload_json=?,version=version+1 WHERE action_id=? AND phase=? AND version=?",
                                item.planVersion(), item.planHash(), item.state().name(), item.outcome().name(),
                                item.baselineAtMillis(), item.appliedAtMillis(), item.nextWindowAtMillis(), item.deadlineAtMillis(),
                                item.windowsSeen(), item.consecutiveRecoveredWindows(), toJson(item), item.actionId(),
                                item.phase().name(), item.version());
                    }
                    if (changed != 1) { throw new IllegalStateException("business verification version is stale or duplicate"); }
                    return item.withVersion(item.version() + 1);
                });
            }
        }

        @Override public List<ActionEffectVerification> due(long nowMillis, int limit) {
            if (limit < 1 || limit > 1000) { throw new IllegalArgumentException("verification batch limit is invalid"); }
            synchronized (JdbcAgentStore.this) {
                return query("SELECT payload_json,version FROM agent_action_verification WHERE state='PREPARED' "
                                + "OR (state='OBSERVING' AND next_window_at<=?) ORDER BY CASE WHEN state='PREPARED' "
                                + "THEN baseline_at ELSE next_window_at END,action_id LIMIT ?",
                        row -> fromJson(row.getString(1), ActionEffectVerification.class).withVersion(row.getLong(2)),
                        nowMillis, limit);
            }
        }
    }

    public synchronized Optional<Job> claimJob(String taskId, long expectedAttempt) {
        return inTransaction(() -> {
            if (run("UPDATE agent_job SET state='RUNNING',attempt=attempt+1 WHERE task_id=? AND state='PENDING' AND attempt=? AND not_before<=?",
                    taskId, expectedAttempt, System.currentTimeMillis()) == 0) { return Optional.empty(); }
            return queryOne("SELECT * FROM agent_job WHERE task_id=?", JdbcAgentStore::readJob, taskId);
        });
    }

    public synchronized void finishJob(String taskId, long attempt) {
        run("UPDATE agent_job SET state='DONE' WHERE task_id=? AND attempt=? AND state='RUNNING'", taskId, attempt);
    }

    /** Cancel a job that has not yet reached the task registry and persist its cancellation atomically. */
    public synchronized boolean cancelUnregisteredJob(String taskId, Runnable cancellation) {
        return inTransaction(() -> {
            if (run("UPDATE agent_job SET state='DONE' WHERE task_id=? AND state IN ('PENDING','RUNNING')", taskId) != 1) {
                return false;
            }
            cancellation.run();
            return true;
        });
    }

    public synchronized void saveBudget(String taskId, long attempt, Object state) {
        if (run("UPDATE agent_job SET budget_json=? WHERE task_id=? AND attempt=? AND state='RUNNING'", toJson(state), taskId, attempt) != 1) {
            throw new IllegalStateException("任务 attempt 已失效，停止迟到执行");
        }
    }

    public synchronized boolean currentAttempt(String taskId, long attempt) {
        return queryOne("SELECT attempt FROM agent_job WHERE task_id=? AND attempt=? AND state='RUNNING'", row -> row.getLong(1), taskId, attempt).isPresent();
    }

    public synchronized void withCurrentAttempt(String taskId, long attempt, Runnable write) {
        inTransaction(() -> {
            if (!currentAttempt(taskId, attempt)) { throw new IllegalStateException("任务 attempt 已过期"); }
            write.run(); return null;
        });
    }

    private void recoverJobs() {
        // 仅在同一个 Admin 的重启路径调用；不支持并发启动第二个 Agent Admin。
        run("UPDATE agent_job SET state='DONE' WHERE state IN ('PENDING','RUNNING') AND task_id IN "
                + "(SELECT task_id FROM agent_task WHERE status NOT IN ('PENDING','RUNNING'))");
        run("UPDATE agent_job SET state='PENDING' WHERE state='RUNNING'");
    }

    private void recoverActionCommands() {
        run("UPDATE agent_action_command SET state='PENDING' WHERE state='RUNNING'");
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
                        session.createdAtMillis(), session.lastActiveAtMillis(), toJson(session.scope()), session.targetNamespace(), session.sessionId()};
                // 会话上的事件列表不落库：它由 agent_incident.session_id 派生，存两份只会有一天对不上。
                upsert("UPDATE agent_session SET user_id = ?, title = ?, active_incident_id = ?, status = ?, "
                                + "created_at = ?, last_active_at = ?, scope_json = ?, target_namespace = ? WHERE session_id = ?",
                        "INSERT INTO agent_session (user_id, title, active_incident_id, status, created_at, "
                                + "last_active_at, scope_json, target_namespace, session_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
                row.createdAtMillis(), row.lastActiveAtMillis(), incidentIds).withScope(row.scope(), row.targetNamespace());
    }

    private static SessionRow readSessionRow(ResultSet rows) throws SQLException {
        return new SessionRow(rows.getString("session_id"), blankToNull(rows.getString("user_id")),
                rows.getString("title"), blankToNull(rows.getString("active_incident_id")),
                SessionStatus.valueOf(rows.getString("status")), rows.getLong("created_at"),
                rows.getLong("last_active_at"), fromJson(rows.getString("scope_json"), com.rover.agent.core.model.ResourceScope.class), Texts.raw(rows.getString("target_namespace")));
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
                        incident.createdAtMillis(), System.currentTimeMillis(), incident.triggeredBy(),
                        incident.incidentId()};
                upsert("UPDATE agent_incident SET session_id = ?, origin = ?, status = ?, title = ?, summary = ?, "
                                + "target_type = ?, target_key = ?, time_from = ?, time_to = ?, created_at = ?, "
                                + "updated_at = ?, triggered_by = ?, version = version + 1 WHERE incident_id = ?",
                        "INSERT INTO agent_incident (session_id, origin, status, title, summary, target_type, "
                                + "target_key, time_from, time_to, created_at, updated_at, triggered_by, version, incident_id) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)",
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
                row.target(), row.timeRange(), row.summary(), row.createdAtMillis(), row.updatedAtMillis(), taskIds,
                row.triggeredBy());
    }

    private static IncidentRow readIncidentRow(ResultSet rows) throws SQLException {
        // 未指定时间范围落库时是两列 NULL：读回来仍是 TimeRange.unspecified()，语义与运行时一致。
        boolean unspecified = rows.getObject("time_from") == null;
        TimeRange timeRange = unspecified ? TimeRange.unspecified()
                : new TimeRange(rows.getLong("time_from"), rows.getLong("time_to"));
        return new IncidentRow(rows.getString("incident_id"), rows.getString("session_id"),
                IncidentOrigin.valueOf(rows.getString("origin")), IncidentStatus.valueOf(rows.getString("status")),
                rows.getString("title"), Texts.raw(rows.getString("summary")), readTarget(rows), timeRange,
                rows.getLong("created_at"), rows.getLong("updated_at"), rows.getString("triggered_by"));
    }

    // ---------------------------------------------------------------- 任务

    private final class Tasks implements AgentTaskRepository {

        @Override public boolean durableEvents() { return true; }

        @Override public void saveEvent(TaskView task, com.rover.agent.core.event.TaskEvent event) {
            synchronized (JdbcAgentStore.this) {
                inTransaction(() -> {
                    if (event.type() != com.rover.agent.core.event.TaskEventType.ANALYSIS_DELTA
                            && event.type() != com.rover.agent.core.event.TaskEventType.THINKING_DELTA) {
                        writeTask(task);
                        writeSteps(task);
                        writeEvidence(task.taskId(), task.evidence());
                    }
                    run("INSERT INTO agent_task_outbox (task_id,event_id,event_json,created_at) VALUES(?,?,?,?)",
                            task.taskId(), event.eventId(), toJson(event), event.timestampMillis());
                    var previous = streamSnapshot(task.taskId()).orElse(new com.rover.agent.core.event.TaskSnapshot(task, "", 0, ""));
                    String text = event.payload() instanceof Map<?,?> map ? String.valueOf(map.getOrDefault("text", null)) : "";
                    String analysis = previous.analysis() + (event.type() == com.rover.agent.core.event.TaskEventType.ANALYSIS_DELTA ? text : "");
                    String thinking = previous.thinking() + (event.type() == com.rover.agent.core.event.TaskEventType.THINKING_DELTA ? text : "");
                    run("MERGE INTO agent_task_stream(task_id,event_id,analysis_text,thinking_text) KEY(task_id) VALUES(?,?,?,?)",
                            task.taskId(), event.eventId(), analysis, thinking);
                    return null;
                });
            }
        }

        @Override public List<com.rover.agent.core.event.TaskEvent> eventsAfter(String taskId, long eventId, int limit) {
            synchronized (JdbcAgentStore.this) {
                return query("SELECT event_json FROM agent_task_outbox WHERE task_id=? AND event_id>? ORDER BY event_id LIMIT ?",
                        row -> fromJson(row.getString(1), com.rover.agent.core.event.TaskEvent.class),
                        taskId, eventId, Math.max(1, Math.min(1000, limit)));
            }
        }

        @Override public Optional<com.rover.agent.core.event.TaskSnapshot> streamSnapshot(String taskId) {
            synchronized (JdbcAgentStore.this) {
                return find(taskId).flatMap(task -> queryOne("SELECT * FROM agent_task_stream WHERE task_id=?",
                        row -> new com.rover.agent.core.event.TaskSnapshot(task, row.getString("analysis_text"), row.getLong("event_id"), row.getString("thinking_text")), taskId));
            }
        }

        @Override
        public void save(TaskView task) {
            synchronized (JdbcAgentStore.this) {
                inTransaction(() -> {
                    writeTask(task);
                    writeSteps(task);
                    writeEvidence(task.taskId(), task.evidence());
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

    // ---------------------------------------------------------------- 安全恢复点

    /** 恢复点的 JDBC 存储，与任务状态、步骤和证据共用事务。 */
    private final class Checkpoints implements AgentCheckpointRepository {

        @Override
        public void save(TaskView task, AgentCheckpoint checkpoint) {
            synchronized (JdbcAgentStore.this) {
                inTransaction(() -> {
                    writeTask(task);
                    writeSteps(task);
                    writeEvidence(task.taskId(), task.evidence());
                    writeCheckpoint(checkpoint);
                    return null;
                });
            }
        }

        @Override
        public Optional<AgentCheckpoint> latest(String taskId) {
            synchronized (JdbcAgentStore.this) {
                return queryOne("SELECT * FROM agent_checkpoint WHERE task_id = ? ORDER BY sequence_no DESC LIMIT 1",
                        JdbcAgentStore::readCheckpointRow, taskId);
            }
        }

        @Override
        public List<AgentCheckpoint> byTask(String taskId) {
            synchronized (JdbcAgentStore.this) {
                return query("SELECT * FROM agent_checkpoint WHERE task_id = ? ORDER BY sequence_no",
                        JdbcAgentStore::readCheckpointRow, taskId);
            }
        }

        @Override
        public int removeByTask(String taskId) {
            synchronized (JdbcAgentStore.this) {
                return run("DELETE FROM agent_checkpoint WHERE task_id = ?", taskId);
            }
        }
    }

    private void writeCheckpoint(AgentCheckpoint checkpoint) {
        run("INSERT INTO agent_checkpoint (checkpoint_id, task_id, sequence_no, stage, round_no, tool_call_count, "
                + "last_step_sequence, last_evidence_sequence, resume_state_json, runtime_node, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                checkpoint.checkpointId(), checkpoint.taskId(), checkpoint.sequenceNo(), checkpoint.stage().name(),
                checkpoint.roundNo(), checkpoint.toolCallCount(), checkpoint.lastStepSequence(),
                checkpoint.lastEvidenceSequence(), blankToNull(checkpoint.resumeStateJson()),
                blankToNull(checkpoint.runtimeNode()), checkpoint.createdAtMillis());
    }

    private static AgentCheckpoint readCheckpointRow(ResultSet rows) throws SQLException {
        return new AgentCheckpoint(rows.getString("checkpoint_id"), rows.getString("task_id"),
                rows.getInt("sequence_no"), CheckpointStage.valueOf(rows.getString("stage")), rows.getInt("round_no"),
                rows.getInt("tool_call_count"), rows.getInt("last_step_sequence"),
                rows.getInt("last_evidence_sequence"), Texts.raw(rows.getString("resume_state_json")),
                Texts.raw(rows.getString("runtime_node")), rows.getLong("created_at"));
    }

    // ---------------------------------------------------------------- 变更记录

    /** 变更记录的 JDBC 存储，通过条件 UPDATE 的影响行数实现状态 CAS。 */
    private final class Actions implements AgentActionRepository {

        @Override
        public AgentAction save(AgentAction action) {
            if (writeAction(action, null) == 0) {
                throw new ActionUpdateConflictException(action.actionId());
            }
            return action.withVersion(Math.addExact(action.version(), 1L));
        }

        @Override
        public Optional<AgentAction> find(String actionId) {
            synchronized (JdbcAgentStore.this) {
                return queryOne("SELECT * FROM agent_action WHERE action_id = ?", JdbcAgentStore.this::readActionRow,
                        actionId);
            }
        }

        @Override
        public List<AgentAction> bySession(String sessionId) {
            synchronized (JdbcAgentStore.this) {
                return query("SELECT * FROM agent_action WHERE session_id = ? ORDER BY created_at DESC, action_id",
                        JdbcAgentStore.this::readActionRow, sessionId);
            }
        }

        @Override public List<AgentAction> byStatus(java.util.Set<ActionStatus> statuses, int limit) {
            if (statuses == null || statuses.isEmpty() || limit < 1 || limit > 1000) {
                throw new IllegalArgumentException("action status query is invalid");
            }
            synchronized (JdbcAgentStore.this) {
                String marks = String.join(",", java.util.Collections.nCopies(statuses.size(), "?"));
                Object[] values = new Object[statuses.size() + 1];
                int index = 0;
                for (ActionStatus status : statuses) { values[index++] = status.name(); }
                values[index] = limit;
                return query("SELECT * FROM agent_action WHERE status IN (" + marks
                                + ") ORDER BY updated_at DESC, action_id LIMIT ?", JdbcAgentStore.this::readActionRow, values);
            }
        }

        @Override
        public List<RemediationPlan> planVersions(String planId) {
            synchronized (JdbcAgentStore.this) {
                return query("SELECT plan_json FROM agent_remediation_plan WHERE plan_id = ? ORDER BY plan_version",
                        rows -> fromJson(rows.getString("plan_json"), RemediationPlan.class), planId);
            }
        }

        @Override
        public List<ActionApproval> approvals(String actionId, ActionApproval.Stage stage, int planVersion,
                                              String planHash, String policyVersion) {
            synchronized (JdbcAgentStore.this) {
                return query("SELECT * FROM agent_action_approval WHERE action_id=? AND approval_stage=? "
                                + "AND plan_version=? AND plan_hash=? AND policy_version=? "
                                + "ORDER BY approved_at, approver",
                        row -> new ActionApproval(row.getString("action_id"),
                                ActionApproval.Stage.valueOf(row.getString("approval_stage")),
                                row.getInt("plan_version"), row.getString("plan_hash"),
                                row.getString("policy_version"), row.getString("approver"), row.getLong("approved_at")),
                        actionId, stage.name(), planVersion, planHash, policyVersion);
            }
        }

        @Override
        public boolean recordApproval(ActionApproval approval) {
            synchronized (JdbcAgentStore.this) {
                return inTransaction(() -> {
                    AgentAction current = actions.find(approval.actionId()).orElseThrow(() ->
                            new ActionUpdateConflictException(approval.actionId()));
                    ActionStatus expected = approval.stage() == ActionApproval.Stage.APPLY
                            ? ActionStatus.PENDING_APPROVAL : ActionStatus.SUCCESS;
                    int planVersion = current.plan() == null ? ActionApproval.LEGACY_PLAN_VERSION
                            : current.plan().planVersion();
                    String planHash = current.plan() == null ? ActionApproval.LEGACY_PLAN_HASH
                            : current.plan().planHash();
                    if (current.status() != expected || approval.planVersion() != planVersion
                            || !approval.planHash().equals(planHash)) {
                        throw new ActionUpdateConflictException(approval.actionId());
                    }
                    try (PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO agent_action_approval(action_id,approval_stage,plan_version,plan_hash,"
                                    + "policy_version,approver,approved_at) VALUES(?,?,?,?,?,?,?)")) {
                        statement.setString(1, approval.actionId());
                        statement.setString(2, approval.stage().name());
                        statement.setInt(3, approval.planVersion());
                        statement.setString(4, approval.planHash());
                        statement.setString(5, approval.policyVersion());
                        statement.setString(6, approval.approver());
                        statement.setLong(7, approval.approvedAtMillis());
                        statement.executeUpdate();
                    } catch (SQLException ex) {
                        if ("23505".equals(ex.getSQLState())) { return false; }
                        throw new IllegalStateException("保存审批回执失败", ex);
                    }
                    String namespace = sessions.find(current.sessionId()).map(Session::targetNamespace).orElse("");
                    appendAudit("ACTION_APPROVAL_RECORDED", approval.actionId(), approval.approver(), namespace, approval);
                    return true;
                });
            }
        }

        @Override
        public Optional<AgentAction> transition(String actionId, ActionStatus expected,
                                                UnaryOperator<AgentAction> mutation) {
            synchronized (JdbcAgentStore.this) {
                AgentAction current = queryOne("SELECT * FROM agent_action WHERE action_id = ?",
                        JdbcAgentStore.this::readActionRow, actionId).orElse(null);
                if (current == null || current.status() != expected) {
                    return Optional.empty();
                }
                AgentAction moved = mutation.apply(current);
                if (!actionId.equals(moved.actionId())) {
                    throw new IllegalArgumentException("状态迁移不能改变变更 ID");
                }
                moved = moved.withVersion(current.version());
                return writeAction(moved, expected) == 0 ? Optional.empty()
                        : Optional.of(moved.withVersion(Math.addExact(current.version(), 1L)));
            }
        }

        @Override
        public int removeBySession(String sessionId) {
            synchronized (JdbcAgentStore.this) {
                return run("DELETE FROM agent_action WHERE session_id = ?", sessionId);
            }
        }
    }

    private final class ActionCommands implements ActionCommandRepository {

        @Override
        public void enqueue(ActionExecutionCommand command) {
            synchronized (JdbcAgentStore.this) {
                inTransaction(() -> {
                    ActionCommandRepository.Job existing = queryOne("SELECT * FROM agent_action_command WHERE command_id=?",
                            JdbcAgentStore::readActionCommandJob, command.commandId()).orElse(null);
                    if (existing != null) {
                        if ("DONE".equals(existing.state())) {
                            run("UPDATE agent_action_command SET state='PENDING',actor=?,"
                                            + "created_at=?,not_before=0,last_error=NULL WHERE command_id=? AND state='DONE'",
                                    command.actor(), command.createdAtMillis(), command.commandId());
                            appendAudit("ACTION_COMMAND_REQUEUED", command.actionId(), command.actor(),
                                    command.targetNamespace(), command);
                        }
                        return null;
                    }
                    run("INSERT INTO agent_action_command(command_id,action_id,action_type,phase,scope_json,"
                                    + "control_target_id,target_namespace,plan_version,plan_hash,policy_version,operation_id,actor,"
                                    + "attempt,state,not_before,created_at,last_error) "
                                    + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,0,'PENDING',0,?,NULL)",
                            command.commandId(), command.actionId(), command.actionType().name(), command.phase().name(),
                            toJson(command.scope()), command.controlTargetId(), command.targetNamespace(),
                            command.planVersion(), command.planHash(), command.policyVersion(), command.operationId(), command.actor(),
                            command.createdAtMillis());
                    appendAudit("ACTION_COMMAND_QUEUED", command.actionId(), command.actor(),
                            command.targetNamespace(), command);
                    return null;
                });
            }
        }

        @Override
        public List<ActionCommandRepository.Job> pending(long nowMillis, int limit) {
            if (limit < 1 || limit > 1000) { throw new IllegalArgumentException("command batch limit is invalid"); }
            synchronized (JdbcAgentStore.this) {
                return query("SELECT * FROM agent_action_command WHERE state='PENDING' AND not_before<=? "
                                + "ORDER BY created_at,command_id LIMIT ?", JdbcAgentStore::readActionCommandJob,
                        nowMillis, limit);
            }
        }

        @Override
        public Optional<ActionCommandRepository.Job> claim(String commandId, long expectedAttempt) {
            synchronized (JdbcAgentStore.this) {
                int changed = run("UPDATE agent_action_command SET state='RUNNING',attempt=attempt+1 "
                                + "WHERE command_id=? AND state='PENDING' AND attempt=? AND not_before<=?",
                        commandId, expectedAttempt, System.currentTimeMillis());
                return changed == 1 ? queryOne("SELECT * FROM agent_action_command WHERE command_id=?",
                        JdbcAgentStore::readActionCommandJob, commandId) : Optional.empty();
            }
        }

        @Override
        public void finish(String commandId, long attempt) {
            synchronized (JdbcAgentStore.this) {
                if (run("UPDATE agent_action_command SET state='DONE',last_error=NULL "
                                + "WHERE command_id=? AND state='RUNNING' AND attempt=?", commandId, attempt) != 1) {
                    throw new IllegalStateException("action command attempt is stale");
                }
            }
        }

        @Override
        public void retry(String commandId, long attempt, long notBeforeMillis, String reason) {
            synchronized (JdbcAgentStore.this) {
                if (run("UPDATE agent_action_command SET state='PENDING',not_before=?,last_error=? "
                                + "WHERE command_id=? AND state='RUNNING' AND attempt=?",
                        notBeforeMillis, reason, commandId, attempt) != 1) {
                    throw new IllegalStateException("action command attempt is stale");
                }
            }
        }
    }

    /**
     * 按行版本写入；更新失败不能退化为插入，避免复活已删除记录。
     *
     * @param expectedStatus 非空时作为更新的状态条件（CAS）；返回受影响行数，0 表示状态已被别人改过
     */
    private int writeAction(AgentAction action, ActionStatus expectedStatus) {
        synchronized (this) {
            return inTransaction(() -> {
                AgentAction current = actions.find(action.actionId()).orElse(null);
                if (current == null ? action.version() != 0
                        : action.version() == 0 || action.version() != current.version()) { return 0; }
                if (expectedStatus != null && (current == null || current.status() != expectedStatus)) { return 0; }
                if (current != null) {
                    action.requireSameIntent(current);
                    if (current.status() == ActionStatus.SUPERSEDED && action.status() != current.status()) {
                        throw new ActionUpdateConflictException(action.actionId());
                    }
                    if (action.plan() != null && action.status() == ActionStatus.EXECUTING
                            && latestPlan(action.plan().planId()).planVersion() != action.plan().planVersion()) {
                        throw new ActionUpdateConflictException(action.actionId());
                    }
                } else {
                    // 删除工作区只删除运行记录；不得重用历史动作 ID 继承旧计划或审批权限。
                    if (planForAction(action.actionId()).isPresent()) {
                        throw new ActionUpdateConflictException(action.actionId());
                    }
                    AgentAction parent = validateNewPlan(action);
                    if (parent != null) {
                        AgentAction superseded = parent.superseded(action.createdAtMillis());
                        if (writeActionRow(superseded, ActionStatus.PENDING_APPROVAL) != 1) {
                            throw new ActionUpdateConflictException(parent.actionId());
                        }
                        auditAction(superseded);
                    }
                }
                int changed = writeActionRow(action, expectedStatus);
                if (changed != 0) {
                    if (current == null && action.plan() != null) {
                        RemediationPlan plan = action.plan();
                        run("INSERT INTO agent_remediation_plan(plan_id,plan_version,action_id,session_id,plan_hash,plan_json,created_at) "
                                        + "VALUES(?,?,?,?,?,?,?)", plan.planId(), plan.planVersion(), action.actionId(),
                                action.sessionId(), plan.planHash(), toJson(plan), plan.createdAtMillis());
                        appendAudit("REMEDIATION_PLAN_CREATED", plan.planId(),
                                com.rover.agent.core.util.AuditActor.current(action.requestedBy()),
                                sessions.find(action.sessionId()).map(Session::targetNamespace).orElse(""), plan);
                    }
                    auditAction(action);
                }
                return changed;
            });
        }
    }

    private void auditAction(AgentAction action) {
        appendAudit("ACTION_" + action.status().name(), action.actionId(),
                com.rover.agent.core.util.AuditActor.current(action.approvedBy() == null ? action.requestedBy() : action.approvedBy()),
                sessions.find(action.sessionId()).map(Session::targetNamespace).orElse(""),
                action.withVersion(Math.addExact(action.version(), 1L)));
    }

    private Optional<RemediationPlan> planForAction(String actionId) {
        return queryOne("SELECT plan_json FROM agent_remediation_plan WHERE action_id = ?",
                rows -> fromJson(rows.getString("plan_json"), RemediationPlan.class), actionId);
    }

    private RemediationPlan latestPlan(String planId) {
        List<RemediationPlan> versions = actions.planVersions(planId);
        return versions.isEmpty() ? null : versions.get(versions.size() - 1);
    }

    private AgentAction validateNewPlan(AgentAction action) {
        RemediationPlan plan = action.plan();
        if (plan == null) { return null; }
        plan.requireMatches(action);
        if (action.status() != ActionStatus.PENDING_APPROVAL || action.applyOperationId() != null
                || action.rollbackOperationId() != null || action.approvedAtMillis() != 0) {
            throw new IllegalArgumentException("新计划必须从待批准状态开始");
        }
        RemediationPlan previous = latestPlan(plan.planId());
        if (previous == null) {
            if (plan.planVersion() != 1) { throw new ActionUpdateConflictException(action.actionId()); }
            return null;
        }
        if (plan.planVersion() != previous.planVersion() + 1) {
            throw new ActionUpdateConflictException(action.actionId());
        }
        String parentId = queryOne("SELECT action_id FROM agent_remediation_plan WHERE plan_id = ? AND plan_version = ?",
                rows -> rows.getString("action_id"), plan.planId(), previous.planVersion()).orElseThrow();
        AgentAction parent = actions.find(parentId).orElse(null);
        if (parent == null || parent.status() != ActionStatus.PENDING_APPROVAL
                || !Objects.equals(parent.sessionId(), action.sessionId())
                || !Objects.equals(parent.resourceRef(), action.resourceRef())
                || !Objects.equals(parent.routeId(), action.routeId())
                || !Objects.equals(parent.businessPrefix(), action.businessPrefix())
                || !Objects.equals(parent.serviceName(), action.serviceName())
                || !Objects.equals(parent.group(), action.group()) || parent.type() != action.type()) {
            throw new ActionUpdateConflictException(action.actionId());
        }
        return parent;
    }

    private int writeActionRow(AgentAction action, ActionStatus expectedStatus) {
        synchronized (JdbcAgentStore.this) {
            Object[] values = {action.sessionId(), action.incidentId(), action.taskId(), action.type().name(),
                    action.status().name(), action.routeId(), blankToNull(action.businessPrefix()),
                    blankToNull(action.serviceName()), blankToNull(action.group()), action.beforeWeight(),
                    action.desiredWeight(), action.requestedValue(), unitName(action.requestedUnit()),
                    action.beforeTrafficPercent(), action.desiredTrafficPercent(),
                    action.expectedRevision(), action.applyOperationId(),
                    action.appliedRevision(), action.rollbackOperationId(), action.requestedBy(),
                    action.approvedBy(), action.approvedAtMillis(), toJson(action.preview()), action.impact(),
                    action.errorMessage(), action.createdAtMillis(), action.updatedAtMillis(), toJson(action.resourceRef())};
            String update = "UPDATE agent_action SET session_id = ?, incident_id = ?, task_id = ?, action_type = ?, "
                    + "status = ?, route_id = ?, business_prefix = ?, service_name = ?, target_group = ?, "
                    + "before_weight = ?, desired_weight = ?, requested_value = ?, requested_unit = ?, "
                    + "before_traffic_percent = ?, desired_traffic_percent = ?, expected_revision = ?, "
                    + "apply_operation_id = ?, "
                    + "applied_revision = ?, rollback_operation_id = ?, requested_by = ?, approved_by = ?, "
                    + "approved_at = ?, preview_json = ?, impact = ?, error_message = ?, created_at = ?, "
                    + "updated_at = ?, resource_ref_json = ?, version = version + 1 WHERE action_id = ? AND version = ?";
            Object[] updateValues = append(append(values, action.actionId()), action.version());
            if (expectedStatus != null) {
                return run(update + " AND status = ?", append(updateValues, expectedStatus.name()));
            }
            if (action.version() > 0) {
                return run(update, updateValues);
            }
            if (queryOne("SELECT * FROM agent_action WHERE action_id = ?", JdbcAgentStore.this::readActionRow,
                    action.actionId()).isPresent()) {
                return 0;
            }
            run("INSERT INTO agent_action (session_id, incident_id, task_id, action_type, status, route_id, "
                            + "business_prefix, service_name, target_group, before_weight, desired_weight, "
                            + "requested_value, requested_unit, before_traffic_percent, desired_traffic_percent, "
                            + "expected_revision, apply_operation_id, applied_revision, rollback_operation_id, "
                            + "requested_by, approved_by, approved_at, preview_json, impact, error_message, "
                            + "created_at, updated_at, resource_ref_json, version, action_id) "
                            + "VALUES (" + "?, ".repeat(values.length) + "1, ?)",
                    append(values, action.actionId()));
            return 1;
        }
    }

    private AgentAction readActionRow(ResultSet rows) throws SQLException {
        return new AgentAction(rows.getString("action_id"), rows.getString("session_id"),
                rows.getString("incident_id"), rows.getString("task_id"),
                ActionType.valueOf(rows.getString("action_type")), ActionStatus.valueOf(rows.getString("status")),
                Texts.raw(rows.getString("route_id")), Texts.raw(rows.getString("business_prefix")),
                Texts.raw(rows.getString("service_name")), Texts.raw(rows.getString("target_group")),
                rows.getInt("before_weight"), rows.getInt("desired_weight"),
                rows.getInt("requested_value"), unitOrNull(rows, "requested_unit"),
                rows.getDouble("before_traffic_percent"), rows.getDouble("desired_traffic_percent"),
                rows.getInt("expected_revision"),
                rows.getString("apply_operation_id"), intOrNull(rows, "applied_revision"),
                rows.getString("rollback_operation_id"), rows.getString("requested_by"),
                rows.getString("approved_by"), rows.getLong("approved_at"),
                orEmpty(fromJson(rows.getString("preview_json"), STRINGS)), Texts.raw(rows.getString("impact")),
                rows.getString("error_message"), rows.getLong("created_at"), rows.getLong("updated_at"),
                rows.getLong("version")).withResourceRef(fromJson(rows.getString("resource_ref_json"), com.rover.agent.core.model.ResourceRef.class))
                .withPlan(planForAction(rows.getString("action_id")).orElse(null));
    }

    /** 单位列：历史数据没有单位时存 NULL，读回来也是 null，不要退化成某个默认值。 */
    private static String unitName(WeightRequestUnit unit) {
        return unit == null ? null : unit.name();
    }

    private static WeightRequestUnit unitOrNull(ResultSet rows, String column) throws SQLException {
        String value = rows.getString(column);
        return value == null || value.isBlank() ? null : WeightRequestUnit.parse(value);
    }

    /** 可空整数列：列值为 NULL 时返回 null，而不是 0（0 是合法的版本号）。 */
    private static Integer intOrNull(ResultSet rows, String column) throws SQLException {
        int value = rows.getInt(column);
        return rows.wasNull() ? null : value;
    }

    private List<TaskView> assembleAll(List<TaskRow> rows) {
        return rows.stream().map(this::assembleTask).toList();
    }

    private TaskView assembleTask(TaskRow row) {
        List<Step> steps = query("SELECT * FROM agent_step WHERE task_id = ? ORDER BY sequence_no, step_id",
                JdbcAgentStore::readStep, row.taskId());
        // 证据只读一次：视图与结论用的是同一批，任务跑到一半（结论还没合成）时也一样能读回来。
        List<Evidence> evidence = query("SELECT * FROM agent_evidence WHERE task_id = ? "
                + "ORDER BY sequence_no, evidence_id", JdbcAgentStore::readEvidence, row.taskId());
        return new TaskView(row.taskId(), row.sessionId(), row.incidentId(), row.status(), row.currentStage(),
                Texts.raw(row.path()), row.target(), row.question(), row.createdAtMillis(), row.completedAtMillis(),
                steps, assembleReport(row, evidence), row.error(), row.clarification(), row.taskType(),
                fromJson(row.planJson(), InvestigationPlan.class), orEmpty(fromJson(row.executedJson(),
                        CAPABILITIES)), orEmpty(fromJson(row.recallsJson(), RECALLS)), evidence,modelInvocations(row.taskId()));
    }

    /** 模型调用与预算共用同一持久账本，避免两份状态在崩溃点不一致；旧任务没有账本则返回空。 */
    private List<com.rover.agent.core.model.ModelInvocation> modelInvocations(String taskId) {
        return queryOne("SELECT budget_json FROM agent_job WHERE task_id=? AND budget_json IS NOT NULL",row->row.getString(1),taskId)
                .map(json->fromJson(json,com.rover.agent.runtime.task.AgentRunBudget.State.class).modelInvocations()).orElse(List.of());
    }

    /**
     * 报告只要有一项内容就还原；结论、置信度、解读与报告的局部结构全空时为 {@code null}，与运行时一致
     * （任务跑到一半就是这种形态：步骤与证据都在，只是还没有结论）。
     */
    private InvestigationReport assembleReport(TaskRow row, List<Evidence> evidence) {
        if (row.resultSummary() == null && row.resultConfidence() == null && row.resultAiAnalysis() == null
                && row.resultLimitationsJson() == null && row.resultHypothesesJson() == null) {
            return null;
        }
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

    private void writeEvidence(String taskId, List<Evidence> reported) {
        List<Evidence> evidence = reported == null ? List.of() : reported;
        List<Evidence> saved = query("SELECT * FROM agent_evidence WHERE task_id=? ORDER BY sequence_no",
                JdbcAgentStore::readEvidence, taskId);
        if (evidence.size() < saved.size() || !evidence.subList(0, saved.size()).equals(saved)) {
            throw new IllegalArgumentException("已采集证据只允许追加；修正事实必须生成新证据 ID");
        }
        List<String> kept = new ArrayList<>();
        int sequence = 0;
        for (Evidence item : evidence) {
            int order = sequence++;
            kept.add(item.evidenceId());
            Object[] values = {taskId, item.type().name(), order, item.source(), item.title(), item.summary(),
                    item.rawReference(), toJson(Map.of("evidenceEnvelopeVersion", 1, "metadata", item.metadata(),
                            "envelope", item.envelope())), item.observedAtMillis()};
            upsert("UPDATE agent_evidence SET task_id = ?, evidence_type = ?, sequence_no = ?, source = ?, title = ?, "
                            + "summary = ?, raw_reference = ?, payload_json = ?, observed_at = ? WHERE evidence_id = ?",
                    "INSERT INTO agent_evidence (task_id, evidence_type, sequence_no, source, title, summary, "
                            + "raw_reference, payload_json, observed_at, created_at, evidence_id) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    append(values, item.evidenceId()),
                    withCreatedAt(values, item.evidenceId()));
        }
        prune("agent_evidence", "evidence_id", taskId, kept);
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
        String payload = rows.getString("payload_json");
        com.rover.agent.core.model.EvidenceEnvelope envelope = null;
        Map<String, String> metadata;
        try {
            var json = payload == null || payload.isBlank() ? JSON.createObjectNode() : JSON.readTree(payload);
            if (json.has("evidenceEnvelopeVersion") && json.path("evidenceEnvelopeVersion").isIntegralNumber()) {
                if (json.path("evidenceEnvelopeVersion").asInt() != 1) { throw new IllegalArgumentException("不支持的证据版本"); }
                metadata = JSON.convertValue(json.required("metadata"), new TypeReference<Map<String, String>>() { });
                envelope = JSON.convertValue(json.required("envelope"), com.rover.agent.core.model.EvidenceEnvelope.class);
            } else { metadata = metadataOf(payload); }
        } catch (Exception ex) { throw new SQLException("读取证据契约失败", ex); }
        return new Evidence(rows.getString("evidence_id"), rows.getString("task_id"),
                EvidenceType.valueOf(rows.getString("evidence_type")), rows.getString("source"),
                rows.getString("title"), rows.getString("summary"), rows.getString("raw_reference"),
                metadata, rows.getLong("observed_at"), envelope);
    }

    // ---------------------------------------------------------------- 行投影

    private record SessionRow(String sessionId, String userId, String title, String activeIncidentId,
                              SessionStatus status, long createdAtMillis, long lastActiveAtMillis,
                              com.rover.agent.core.model.ResourceScope scope, String targetNamespace) { }

    private record IncidentRow(String incidentId, String sessionId, IncidentOrigin origin, IncidentStatus status,
                               String title, String summary, ResourceTarget target, TimeRange timeRange,
                               long createdAtMillis, long updatedAtMillis, String triggeredBy) { }

    private record TaskRow(String taskId, String sessionId, String incidentId, TaskType taskType, TaskStatus status,
                           String question, String path, ResourceTarget target, AgentStepType currentStage,
                           String clarification, String error, String planJson, String executedJson,
                           String recallsJson, String resultSummary, String resultConfidence, String resultAiAnalysis,
                           String resultLimitationsJson, String resultHypothesesJson, long createdAtMillis,
                           long completedAtMillis) { }

    // ---------------------------------------------------------------- 迁移

    /** 一条外键：约束名 + 所属表 + 建约束语句（表要先建好，因此不能写在 CREATE TABLE 里）。 */
    private record ForeignKey(String name, String table, String sql) { }

    /** 一条索引：只需名字与建索引语句——判存在性时按名字扫全部 agent 表。 */
    private record Index(String name, String sql) { }

    private record Migration(int version, String description, List<String> statements,
                             List<ForeignKey> foreignKeys, List<Index> indexes) { }

    /**
     * 按版本执行 Schema 迁移；v2 删除旧 JSON 快照表。
     * 索引与外键先检查元数据，保证部分 DDL 已执行后可重试。
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
                                            + "ON DELETE CASCADE")),
                    List.of(
                            new Index("idx_agent_message_session",
                                    "CREATE INDEX idx_agent_message_session ON agent_message(session_id, seq)"),
                            new Index("idx_agent_incident_session",
                                    "CREATE INDEX idx_agent_incident_session ON agent_incident(session_id, created_at)"),
                            new Index("idx_agent_task_session",
                                    "CREATE INDEX idx_agent_task_session ON agent_task(session_id, created_at)"),
                            new Index("idx_agent_task_incident",
                                    "CREATE INDEX idx_agent_task_incident ON agent_task(incident_id)"),
                            new Index("idx_agent_task_status",
                                    "CREATE INDEX idx_agent_task_status ON agent_task(status)"),
                            new Index("idx_agent_step_task",
                                    "CREATE INDEX idx_agent_step_task ON agent_step(task_id, sequence_no)"),
                            new Index("idx_agent_evidence_task",
                                    "CREATE INDEX idx_agent_evidence_task ON agent_evidence(task_id, sequence_no)"))),
            new Migration(2, "retire legacy json stores: investigation / chat_session / chat_message",
                    List.of("DROP TABLE IF EXISTS investigation",
                            "DROP TABLE IF EXISTS chat_session",
                            "DROP TABLE IF EXISTS chat_message"),
                    List.of(), List.of()),
            new Migration(3, "agent checkpoint: safe resume points per task",
                    List.of("CREATE TABLE IF NOT EXISTS agent_checkpoint ("
                                    + "checkpoint_id VARCHAR(64) PRIMARY KEY, "
                                    + "task_id VARCHAR(64) NOT NULL, "
                                    + "sequence_no INT NOT NULL, "
                                    + "stage VARCHAR(32) NOT NULL, "
                                    + "round_no INT NOT NULL, "
                                    + "tool_call_count INT NOT NULL, "
                                    + "last_step_sequence INT NOT NULL, "
                                    + "last_evidence_sequence INT NOT NULL, "
                                    + "resume_state_json TEXT, "
                                    + "runtime_node VARCHAR(64), "
                                    + "created_at BIGINT NOT NULL)"),
                    List.of(new ForeignKey("fk_agent_checkpoint_task", "agent_checkpoint",
                            "ALTER TABLE agent_checkpoint ADD CONSTRAINT fk_agent_checkpoint_task "
                                    + "FOREIGN KEY (task_id) REFERENCES agent_task(task_id) ON DELETE CASCADE")),
                    List.of(new Index("idx_agent_checkpoint_task",
                            "CREATE INDEX idx_agent_checkpoint_task ON agent_checkpoint(task_id, sequence_no)"))),
            new Migration(4, "agent action: proposed, approved and verified route changes",
                    List.of("CREATE TABLE IF NOT EXISTS agent_action ("
                                    + "action_id VARCHAR(64) PRIMARY KEY, "
                                    + "session_id VARCHAR(64) NOT NULL, "
                                    + "incident_id VARCHAR(64), "
                                    + "task_id VARCHAR(64), "
                                    + "action_type VARCHAR(48) NOT NULL, "
                                    + "status VARCHAR(32) NOT NULL, "
                                    + "route_id VARCHAR(255) NOT NULL, "
                                    + "business_prefix VARCHAR(255), "
                                    + "service_name VARCHAR(255), "
                                    + "target_group VARCHAR(128), "
                                    + "before_weight INT NOT NULL, "
                                    + "desired_weight INT NOT NULL, "
                                    + "expected_revision INT NOT NULL, "
                                    + "apply_operation_id VARCHAR(64), "
                                    + "applied_revision INT, "
                                    + "rollback_operation_id VARCHAR(64), "
                                    + "requested_by VARCHAR(128), "
                                    + "approved_by VARCHAR(128), "
                                    + "approved_at BIGINT NOT NULL, "
                                    + "preview_json TEXT, "
                                    + "impact TEXT, "
                                    + "error_message TEXT, "
                                    + "created_at BIGINT NOT NULL, "
                                    + "updated_at BIGINT NOT NULL, "
                                    + "version BIGINT NOT NULL)"),
                    List.of(
                            new ForeignKey("fk_agent_action_session", "agent_action",
                                    "ALTER TABLE agent_action ADD CONSTRAINT fk_agent_action_session "
                                            + "FOREIGN KEY (session_id) REFERENCES agent_session(session_id) "
                                            + "ON DELETE CASCADE"),
                            // 任务被淘汰时把外键置空而不是删掉变更：变更的审计价值高于它的来路链接。
                            new ForeignKey("fk_agent_action_task", "agent_action",
                                    "ALTER TABLE agent_action ADD CONSTRAINT fk_agent_action_task "
                                            + "FOREIGN KEY (task_id) REFERENCES agent_task(task_id) "
                                            + "ON DELETE SET NULL")),
                    List.of(
                            new Index("idx_agent_action_session",
                                    "CREATE INDEX idx_agent_action_session ON agent_action(session_id, created_at)"),
                            new Index("idx_agent_action_status",
                                    "CREATE INDEX idx_agent_action_status ON agent_action(status)"))),
                // 审批卡上被批准的到底是「权重 20」还是「20% 流量」，事后只能靠这几列回答：
                // desired_weight 只是换算结果，不能反推用户意图。
                new Migration(5, "agent action: keep requested value, its unit and traffic percent preview",
                        List.of(
                                "ALTER TABLE agent_action ADD COLUMN IF NOT EXISTS requested_value INT",
                                "ALTER TABLE agent_action ADD COLUMN IF NOT EXISTS requested_unit VARCHAR(24)",
                                "ALTER TABLE agent_action ADD COLUMN IF NOT EXISTS before_traffic_percent DOUBLE",
                                "ALTER TABLE agent_action ADD COLUMN IF NOT EXISTS desired_traffic_percent DOUBLE"),
                        List.of(),
                        List.of()),
                new Migration(6, "incident: retain authenticated trigger separately from handler",
                        List.of("ALTER TABLE agent_incident ADD COLUMN IF NOT EXISTS triggered_by VARCHAR(128)"),
                        List.of(), List.of()),
                new Migration(7, "bind action to resource identity and session scope",
                        List.of("ALTER TABLE agent_action ADD COLUMN IF NOT EXISTS resource_ref_json TEXT",
                                "ALTER TABLE agent_session ADD COLUMN IF NOT EXISTS scope_json TEXT",
                                "ALTER TABLE agent_session ADD COLUMN IF NOT EXISTS target_namespace VARCHAR(64)"),
                        List.of(), List.of()),
                new Migration(8, "transactional action audit and task event outbox",
                        List.of("CREATE TABLE IF NOT EXISTS agent_audit (audit_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
                                        + "event_kind VARCHAR(128) NOT NULL, aggregate_id VARCHAR(128) NOT NULL, actor VARCHAR(128), "
                                        + "resource_namespace TEXT, payload_json TEXT NOT NULL, created_at BIGINT NOT NULL)",
                                "CREATE INDEX IF NOT EXISTS idx_agent_audit_aggregate ON agent_audit(aggregate_id,audit_id)",
                                "CREATE TABLE IF NOT EXISTS agent_task_outbox (task_id VARCHAR(64) NOT NULL, event_id BIGINT NOT NULL, "
                                        + "event_json TEXT NOT NULL, created_at BIGINT NOT NULL, PRIMARY KEY(task_id,event_id))",
                                "CREATE TABLE IF NOT EXISTS agent_task_stream (task_id VARCHAR(64) PRIMARY KEY, event_id BIGINT NOT NULL, analysis_text TEXT NOT NULL, thinking_text TEXT NOT NULL)"),
                        List.of(), List.of()),
                new Migration(9, "single Admin durable read jobs and execution budget",
                        List.of("CREATE TABLE IF NOT EXISTS agent_job (task_id VARCHAR(64) PRIMARY KEY REFERENCES agent_task(task_id) ON DELETE CASCADE, "
                                        + "job_kind VARCHAR(32) NOT NULL, payload_json TEXT NOT NULL, attempt BIGINT NOT NULL, state VARCHAR(32) NOT NULL, "
                                        + "budget_json TEXT, not_before BIGINT NOT NULL)",
                                "CREATE INDEX IF NOT EXISTS idx_agent_job_due ON agent_job(state,not_before)"), List.of(), List.of()),
                new Migration(10, "durable change timeline and source baselines",
                        List.of("CREATE TABLE IF NOT EXISTS agent_change_event (resource_namespace VARCHAR(64) NOT NULL, "
                                        + "event_id VARCHAR(64) NOT NULL, source_id VARCHAR(255) NOT NULL, source_event_id VARCHAR(255) NOT NULL, "
                                        + "event_kind VARCHAR(64) NOT NULL, range_from BIGINT NOT NULL, range_to BIGINT NOT NULL, observed_at BIGINT NOT NULL, "
                                        + "route_id VARCHAR(255) NOT NULL, route_prefix VARCHAR(255) NOT NULL, component VARCHAR(255) NOT NULL, "
                                        + "intent_digest VARCHAR(64) NOT NULL, payload_json TEXT NOT NULL, PRIMARY KEY(resource_namespace,event_id), "
                                        + "UNIQUE(resource_namespace,source_id,source_event_id))",
                                "CREATE INDEX IF NOT EXISTS idx_change_window ON agent_change_event(resource_namespace,range_from,range_to)",
                                "CREATE INDEX IF NOT EXISTS idx_change_route ON agent_change_event(resource_namespace,source_id,route_prefix,range_to)",
                                "CREATE TABLE IF NOT EXISTS agent_change_link (resource_namespace VARCHAR(64) NOT NULL,event_id VARCHAR(64) NOT NULL, "
                                        + "link_kind VARCHAR(32) NOT NULL,resource_id VARCHAR(255) NOT NULL, PRIMARY KEY(resource_namespace,event_id,link_kind,resource_id), "
                                        + "FOREIGN KEY(resource_namespace,event_id) REFERENCES agent_change_event(resource_namespace,event_id) ON DELETE CASCADE)",
                                "CREATE INDEX IF NOT EXISTS idx_change_resource ON agent_change_link(resource_namespace,link_kind,resource_id,event_id)",
                                "CREATE TABLE IF NOT EXISTS agent_timeline_source (resource_namespace VARCHAR(64) NOT NULL,source_id VARCHAR(255) NOT NULL, "
                                        + "payload_json TEXT NOT NULL,PRIMARY KEY(resource_namespace,source_id))"), List.of(), List.of()),
                new Migration(11, "immutable remediation plan versions",
                        List.of("CREATE TABLE IF NOT EXISTS agent_remediation_plan (plan_id VARCHAR(128) NOT NULL, "
                                        + "plan_version INT NOT NULL, action_id VARCHAR(64) NOT NULL UNIQUE, "
                                        + "session_id VARCHAR(64) NOT NULL, plan_hash VARCHAR(64) NOT NULL, "
                                        + "plan_json TEXT NOT NULL, created_at BIGINT NOT NULL, PRIMARY KEY(plan_id,plan_version))",
                                "CREATE INDEX IF NOT EXISTS idx_remediation_plan_session ON agent_remediation_plan(session_id,created_at)"),
                        List.of(), List.of()),
                new Migration(12, "immutable plan and policy scoped approvals",
                        List.of("CREATE TABLE IF NOT EXISTS agent_action_approval ("
                                        + "approval_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
                                        + "action_id VARCHAR(64) NOT NULL, approval_stage VARCHAR(16) NOT NULL, "
                                        + "plan_version INT NOT NULL, plan_hash VARCHAR(64) NOT NULL, "
                                        + "policy_version VARCHAR(128) NOT NULL, approver VARCHAR(128) NOT NULL, "
                                        + "approved_at BIGINT NOT NULL, "
                                        + "CONSTRAINT uq_action_approval UNIQUE(action_id,approval_stage,plan_version,"
                                        + "plan_hash,policy_version,approver), "
                                        + "FOREIGN KEY(action_id) REFERENCES agent_action(action_id) ON DELETE CASCADE)"),
                        List.of(), List.of(new Index("idx_action_approval_lookup",
                                "CREATE INDEX idx_action_approval_lookup ON agent_action_approval"
                                        + "(action_id,approval_stage,plan_version,policy_version)"))),
                new Migration(13, "typed durable action commands by managed target",
                        List.of("CREATE TABLE IF NOT EXISTS agent_action_command ("
                                        + "command_id VARCHAR(256) PRIMARY KEY, action_id VARCHAR(64) NOT NULL, "
                                        + "action_type VARCHAR(64) NOT NULL, phase VARCHAR(16) NOT NULL, "
                                        + "scope_json TEXT, control_target_id VARCHAR(255) NOT NULL, "
                                        + "target_namespace VARCHAR(255) NOT NULL, plan_version INT NOT NULL, "
                                        + "plan_hash VARCHAR(64) NOT NULL, policy_version VARCHAR(128) NOT NULL, "
                                        + "operation_id VARCHAR(64), actor VARCHAR(128), "
                                        + "attempt BIGINT NOT NULL, state VARCHAR(16) NOT NULL, not_before BIGINT NOT NULL, "
                                        + "created_at BIGINT NOT NULL, last_error TEXT, "
                                        + "FOREIGN KEY(action_id) REFERENCES agent_action(action_id) ON DELETE CASCADE)",
                                "CREATE INDEX IF NOT EXISTS idx_action_command_due ON agent_action_command(state,not_before,created_at)",
                                "CREATE INDEX IF NOT EXISTS idx_action_command_target ON agent_action_command(target_namespace,control_target_id,created_at)"),
                        List.of(), List.of()),
                new Migration(14, "separate action business effect verification history",
                        List.of("CREATE TABLE IF NOT EXISTS agent_action_verification ("
                                        + "action_id VARCHAR(64) NOT NULL, phase VARCHAR(24) NOT NULL, "
                                        + "plan_version INT NOT NULL, plan_hash VARCHAR(64) NOT NULL, "
                                        + "state VARCHAR(16) NOT NULL, outcome VARCHAR(32) NOT NULL, "
                                        + "baseline_at BIGINT NOT NULL, applied_at BIGINT NOT NULL, next_window_at BIGINT NOT NULL, "
                                        + "deadline_at BIGINT NOT NULL, windows_seen INT NOT NULL, recovered_windows INT NOT NULL, "
                                        + "payload_json TEXT NOT NULL, version BIGINT NOT NULL, "
                                        + "PRIMARY KEY(action_id,phase), FOREIGN KEY(action_id) REFERENCES agent_action(action_id) ON DELETE CASCADE)",
                                "CREATE INDEX IF NOT EXISTS idx_action_verification_due ON agent_action_verification(state,next_window_at,action_id)"),
                        List.of(), List.of()),
                new Migration(15, "alert delivery idempotency receipts and active lifecycle",
                        List.of("CREATE TABLE IF NOT EXISTS agent_alert_delivery ("
                                        + "source_id VARCHAR(128) NOT NULL, delivery_id VARCHAR(128) NOT NULL, "
                                        + "alert_key VARCHAR(256) NOT NULL, event_state VARCHAR(16) NOT NULL, "
                                        + "payload_fingerprint VARCHAR(64) NOT NULL, generation BIGINT NOT NULL, "
                                        + "session_id VARCHAR(64), incident_id VARCHAR(64), task_id VARCHAR(64), "
                                        + "accepted_at BIGINT NOT NULL, PRIMARY KEY(source_id,delivery_id))",
                                "CREATE TABLE IF NOT EXISTS agent_alert_lifecycle ("
                                        + "source_id VARCHAR(128) NOT NULL, alert_key VARCHAR(256) NOT NULL, "
                                        + "active BOOLEAN NOT NULL, path VARCHAR(512) NOT NULL, generation BIGINT NOT NULL, "
                                        + "session_id VARCHAR(64), incident_id VARCHAR(64), task_id VARCHAR(64), "
                                        + "last_state VARCHAR(16) NOT NULL, last_fingerprint VARCHAR(64), updated_at BIGINT NOT NULL, "
                                        + "PRIMARY KEY(source_id,alert_key))",
                                "CREATE INDEX IF NOT EXISTS idx_alert_delivery_accepted ON agent_alert_delivery(accepted_at)"),
                        List.of(), List.of()),
                new Migration(16, "versioned narrowly scoped autonomous action policies",
                        List.of("CREATE TABLE IF NOT EXISTS agent_auto_policy ("
                                        + "policy_id VARCHAR(64) NOT NULL, policy_version BIGINT NOT NULL, "
                                        + "target_namespace VARCHAR(128) NOT NULL, enabled BOOLEAN NOT NULL, "
                                        + "payload_json TEXT NOT NULL, updated_at BIGINT NOT NULL, "
                                        + "PRIMARY KEY(policy_id,policy_version))",
                                "CREATE INDEX IF NOT EXISTS idx_agent_auto_policy_target ON agent_auto_policy(target_namespace,enabled,policy_id,policy_version)"),
                        List.of(), List.of()),
                new Migration(17, "autonomous route execution lock and rolling impact budget",
                        List.of("CREATE TABLE IF NOT EXISTS agent_action_budget ("
                                        + "resource_key VARCHAR(512) PRIMARY KEY, locked_action VARCHAR(64), locked_reservation VARCHAR(128), "
                                        + "budget_window BIGINT NOT NULL, cooldown_until BIGINT NOT NULL, "
                                        + "reserved_percent DOUBLE NOT NULL, reserved_cooldown BIGINT NOT NULL, "
                                        + "reserved_direction INT NOT NULL, reserved_budgeted BOOLEAN NOT NULL)",
                                "CREATE TABLE IF NOT EXISTS agent_action_budget_event ("
                                        + "resource_key VARCHAR(512) NOT NULL, action_id VARCHAR(64) NOT NULL, reservation_id VARCHAR(64) NOT NULL, "
                                        + "event_order BIGINT GENERATED BY DEFAULT AS IDENTITY NOT NULL, applied_at BIGINT NOT NULL, impact_percent DOUBLE NOT NULL, direction INT NOT NULL, "
                                        + "PRIMARY KEY(resource_key,reservation_id), FOREIGN KEY(resource_key) "
                                        + "REFERENCES agent_action_budget(resource_key) ON DELETE CASCADE)",
                                "CREATE INDEX IF NOT EXISTS idx_action_budget_recent ON agent_action_budget_event(resource_key,applied_at,event_order)"),
                        List.of(), List.of()));

    private void migrate() throws SQLException {
        // applied_at 用毫秒时间戳：与业务表同一口径，也不依赖各驱动对 TIMESTAMP 的绑定细节。
        execute("CREATE TABLE IF NOT EXISTS agent_schema_migrations ("
                + "version INT PRIMARY KEY, description VARCHAR(255), applied_at BIGINT NOT NULL)");
        int current = currentVersion();
        if (current > MIGRATIONS.get(MIGRATIONS.size() - 1).version()) {
            throw new IllegalStateException("数据库版本高于当前程序，禁止降级启动");
        }
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
            for (Index index : migration.indexes()) {
                if (!hasIndex(index.name())) {
                    execute(index.sql());
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
        for (String table : List.of("agent_message", "agent_incident", "agent_task", "agent_step", "agent_evidence",
                "agent_checkpoint", "agent_action", "agent_action_approval")) {
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
     * 重启时将 PENDING/RUNNING 任务标记为 INTERRUPTED，保留步骤、证据和恢复点。
     * 等待澄清的任务不受影响。
     */
    private void recoverInterrupted() {
        synchronized (this) {
            List<TaskRow> leftovers = query("SELECT * FROM agent_task WHERE status IN (?, ?) AND NOT EXISTS "
                            + "(SELECT 1 FROM agent_job WHERE agent_job.task_id=agent_task.task_id AND state='PENDING') ORDER BY created_at",
                    JdbcAgentStore::readTaskRow, TaskStatus.PENDING.name(), TaskStatus.RUNNING.name());
            for (TaskRow row : leftovers) {
                String reason = checkpoints.latest(row.taskId())
                        .map(mark -> INTERRUPTED + "；最近的安全恢复点：" + mark.describe())
                        .orElse(INTERRUPTED + "；没有留下安全恢复点，已采集的步骤仍可查看");
                long now = System.currentTimeMillis();
                run("UPDATE agent_task SET status = ?, error_message = ?, completed_at = ?, updated_at = ?, "
                                + "version = version + 1 WHERE task_id = ?",
                        TaskStatus.INTERRUPTED.name(), reason, now, now, row.taskId());
                log.warn("任务 {} 在重启前仍在执行，已标记为中断（{}）", row.taskId(), reason);
            }
        }
    }

    /**
     * 恢复中断变更：无幂等号判定未提交，有幂等号则标记结果未知。
     * 尚未提交补偿的回滚退回 SUCCESS，允许再次回滚。
     */
    private void recoverInterruptedActions() {
        synchronized (this) {
            inTransaction(() -> {
            List<AgentAction> interrupted = query("SELECT * FROM agent_action WHERE status IN (?,?,?,?)",
                    JdbcAgentStore.this::readActionRow, ActionStatus.EXECUTING.name(), ActionStatus.VERIFYING.name(),
                    ActionStatus.ROLLING_BACK.name(), ActionStatus.RECONCILING.name());
            long now = System.currentTimeMillis();
            int unsent = run("UPDATE agent_action SET status = ?, error_message = ?, updated_at = ?, "
                            + "version = version + 1 WHERE status = ? AND apply_operation_id IS NULL "
                            + "AND NOT EXISTS (SELECT 1 FROM agent_action_command c WHERE c.action_id=agent_action.action_id "
                            + "AND c.state IN ('PENDING','RUNNING'))",
                    ActionStatus.FAILED.name(), RESTART_NOT_SUBMITTED, now, ActionStatus.EXECUTING.name());
            int unknown = run("UPDATE agent_action SET status = ?, error_message = ?, updated_at = ?, "
                            + "version = version + 1 WHERE status IN (?, ?) AND apply_operation_id IS NOT NULL "
                            + "AND NOT EXISTS (SELECT 1 FROM agent_action_command c WHERE c.action_id=agent_action.action_id "
                            + "AND c.state IN ('PENDING','RUNNING'))",
                    ActionStatus.UNCERTAIN.name(), RESTART_UNKNOWN, now, ActionStatus.EXECUTING.name(),
                    ActionStatus.VERIFYING.name());
            int rollbackUnsent = run("UPDATE agent_action SET status = ?, error_message = ?, updated_at = ?, "
                            + "version = version + 1 WHERE status = ? AND rollback_operation_id IS NULL "
                            + "AND NOT EXISTS (SELECT 1 FROM agent_action_command c WHERE c.action_id=agent_action.action_id "
                            + "AND c.state IN ('PENDING','RUNNING'))",
                    ActionStatus.SUCCESS.name(), RESTART_ROLLBACK_NOT_SUBMITTED, now,
                    ActionStatus.ROLLING_BACK.name());
            int rollbackUnknown = run("UPDATE agent_action SET status = ?, error_message = ?, updated_at = ?, "
                            + "version = version + 1 WHERE status = ? AND rollback_operation_id IS NOT NULL "
                            + "AND NOT EXISTS (SELECT 1 FROM agent_action_command c WHERE c.action_id=agent_action.action_id "
                            + "AND c.state IN ('PENDING','RUNNING'))",
                    ActionStatus.UNCERTAIN.name(), RESTART_UNKNOWN, now, ActionStatus.ROLLING_BACK.name());
            run("UPDATE agent_action SET status=?,error_message=?,updated_at=?,version=version+1 "
                            + "WHERE status=? AND NOT EXISTS (SELECT 1 FROM agent_action_command c "
                            + "WHERE c.action_id=agent_action.action_id AND c.state IN ('PENDING','RUNNING'))",
                    ActionStatus.UNCERTAIN.name(), RESTART_UNKNOWN, now, ActionStatus.RECONCILING.name());
            if (unsent + unknown + rollbackUnsent + rollbackUnknown > 0) {
                log.warn("重启前有 {} 条变更停在执行中：{} 条未提交（判为失败）、{} 条结果未知（待确认）",
                        unsent + unknown + rollbackUnsent + rollbackUnknown,
                        unsent + rollbackUnsent, unknown + rollbackUnknown);
            }
            for (AgentAction before : interrupted) {
                AgentAction recovered = actions.find(before.actionId()).orElseThrow();
                if (recovered.version() != before.version()) {
                    appendAudit("ACTION_RESTART_RECOVERED", recovered.actionId(), "system:startup",
                            sessions.find(recovered.sessionId()).map(Session::targetNamespace).orElse(""), recovered);
                }
            }
            return null;
            });
        }
    }

    // ---------------------------------------------------------------- JDBC 基础设施

    private interface SqlWork<T> {
        T run();
    }

    private interface RowMapper<T> {
        T map(ResultSet rows) throws SQLException;
    }

    /** 通过显式 JDBC 事务原子保存任务状态、步骤与证据。 */
    private <T> T inTransaction(SqlWork<T> work) {
        try {
            boolean autoCommit = connection.getAutoCommit();
            if (!autoCommit) { return work.run(); }
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


    private static ResourceTarget targetOf(ResourceTarget target) {
        return target == null ? ResourceTarget.unknown() : target;
    }

    private static ResourceTarget readTarget(ResultSet rows) throws SQLException {
        String type = rows.getString("target_type");
        return type == null ? ResourceTarget.unknown()
                : new ResourceTarget(TargetType.valueOf(type), Texts.raw(rows.getString("target_key")));
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
