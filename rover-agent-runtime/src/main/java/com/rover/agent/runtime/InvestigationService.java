package com.rover.agent.runtime;

import com.rover.agent.core.event.TaskEventSubscriber;
import com.rover.agent.core.event.TaskEventSubscription;
import com.rover.agent.core.model.AlertDelivery;
import com.rover.agent.core.model.AlertEventState;
import com.rover.agent.core.model.AlertIngestResult;
import com.rover.agent.core.model.AlertLifecycle;
import com.rover.agent.core.model.Incident;
import com.rover.agent.core.model.IncidentOrigin;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.model.TimeRange;
import com.rover.agent.core.repository.AlertLifecycleRepository;
import com.rover.agent.runtime.journal.OpsJournal;
import com.rover.agent.runtime.task.IncidentRegistry;
import com.rover.agent.runtime.task.InvestigationTask;
import com.rover.agent.runtime.task.InvestigationTaskRegistry;
import com.rover.agent.runtime.task.AlertDeliveryConflictException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 告警事件登记与生命周期管理；告警调查与人工对话共享 ToolLoopService 的 ReAct 工具循环。
 */
public final class InvestigationService {

    private static final Logger log = LoggerFactory.getLogger(InvestigationService.class);
    private static final int MAX_PATH_LENGTH = 512;
    private static final int MAX_QUESTION_LENGTH = 1000;
    private static final long ALERT_DELIVERY_RETENTION_MILLIS = java.time.Duration.ofDays(90).toMillis();

    private final IncidentRegistry incidents;
    private final InvestigationTaskRegistry tasks;
    private final ToolLoopService toolLoop;
    private final OpsJournal journal;
    private final String alertOwnerUserId;
    private final AlertLifecycleRepository alertLifecycles;

    public InvestigationService(IncidentRegistry incidents, InvestigationTaskRegistry tasks,
                                ToolLoopService toolLoop, OpsJournal journal,
                                String alertOwnerUserId,
                                AlertLifecycleRepository alertLifecycles) {
        this.incidents = incidents;
        this.tasks = tasks;
        this.toolLoop = toolLoop;
        this.journal = journal == null ? OpsJournal.none() : journal;
        this.alertOwnerUserId = identity(alertOwnerUserId);
        this.alertLifecycles = alertLifecycles;
        tasks.handler("ALERT", (task, payload) -> {
            try {
                TimeRange range = new com.fasterxml.jackson.databind.ObjectMapper()
                        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).readValue(payload, TimeRange.class);
                task.bind(task.incidentId(), task.path(), task.target(), range);
                runAlert(task);
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalArgumentException("告警 Job 输入损坏", ex); }
        });
    }

    /**
     * 内部无认证调用的兼容入口；HTTP 接入必须携带认证来源主体。
     * 未指定观测窗口时使用默认值。
     */
    public TaskView submitAlert(String path, String alertMessage, TimeRange timeRange) {
        return submitAlert(null, path, alertMessage, timeRange);
    }

    public TaskView submitAlert(String sourceUserId, String path, String alertMessage, TimeRange timeRange) {
        String triggeredBy = identity(sourceUserId);
        String question = "告警自动调查："
                + (alertMessage == null || alertMessage.isBlank() ? "请根据下方路径与窗口排查失败原因" : alertMessage);
        return submit(path, question, IncidentOrigin.ALERT, timeRange, triggeredBy);
    }

    /** Idempotent alert webhook lifecycle. The source is part of both deduplication keys. */
    public AlertIngestResult submitAlert(String sourceUserId, String deliveryId, String alertKey,
                                         AlertEventState state, String path, String alertMessage, TimeRange timeRange) {
        if (alertLifecycles == null) { throw new IllegalStateException("alert lifecycle repository is unavailable"); }
        String source = requiredIdentity(sourceUserId, "告警来源身份不能为空");
        String delivery = bounded(deliveryId, 128, "deliveryId");
        String key = bounded(alertKey, 256, "alertKey");
        if (state == null) { throw new IllegalArgumentException("告警状态不能为空"); }
        String target = validatePath(path);
        String message = alertMessage == null ? "" : alertMessage.trim();
        if (message.length() > MAX_QUESTION_LENGTH) { throw new IllegalArgumentException("告警文本过长"); }
        TimeRange range = timeRange == null ? TimeRange.unspecified() : timeRange;
        String fingerprint = fingerprint(state.name(), target, message, Long.toString(range.fromMillis()), Long.toString(range.toMillis()));
        String lifecycleFingerprint = fingerprint(target, Long.toString(range.fromMillis()), Long.toString(range.toMillis()));
        long now = System.currentTimeMillis();
        AlertDelivery previous = alertLifecycles.transaction(tx -> tx.delivery(source, delivery).orElse(null));
        if (previous != null && (!previous.alertKey().equals(key) || !previous.payloadFingerprint().equals(fingerprint))) {
            throw new AlertDeliveryConflictException("同一 deliveryId 已用于不同的告警内容");
        }
        if (previous != null) {
            access.require(alertOwnerUserId == null ? source : alertOwnerUserId,
                    com.rover.agent.core.port.AgentAccess.Permission.READ);
            return result(previous, true);
        }
        access.require(alertOwnerUserId == null ? source : alertOwnerUserId,
                com.rover.agent.core.port.AgentAccess.Permission.INGEST);
        AlertIngestResult result;
        synchronized (alertLifecycles) {
            AlertDelivery latest = alertLifecycles.transaction(tx -> tx.delivery(source, delivery).orElse(null));
            if (latest == null && previous != null) {
                throw new AlertDeliveryConflictException("告警投递回执已过期，请按告警生命周期重新生成 delivery ID");
            }
            if (latest != null && (!latest.alertKey().equals(key) || !latest.payloadFingerprint().equals(fingerprint))) {
                throw new AlertDeliveryConflictException("同一 deliveryId 已用于不同的告警内容");
            }
            access.require(alertOwnerUserId == null ? source : alertOwnerUserId,
                    latest == null ? com.rover.agent.core.port.AgentAccess.Permission.INGEST
                            : com.rover.agent.core.port.AgentAccess.Permission.READ);
            result = alertLifecycles.transaction(tx -> {
            tx.pruneDeliveriesBefore(now - ALERT_DELIVERY_RETENTION_MILLIS);
            AlertDelivery existing = tx.delivery(source, delivery).orElse(null);
            if (existing != null) {
                if (!existing.alertKey().equals(key) || !existing.payloadFingerprint().equals(fingerprint)) {
                    throw new AlertDeliveryConflictException("同一 deliveryId 已用于不同的告警内容");
                }
                return result(existing, true);
            }
            AlertLifecycle current = tx.lifecycle(source, key).orElse(null);
            if (state == AlertEventState.RESOLVED && (current == null || !current.active())) {
                throw new IllegalArgumentException("没有可结束的活动告警轮次");
            }
            if (current != null && current.active() && !current.path().equals(target)) {
                throw new AlertDeliveryConflictException("活动告警键不能更换 path；请先发送 RESOLVED，再开启新一轮");
            }
            if (current != null && current.active() && current.lastState() == state
                    && !current.lastPayloadFingerprint().equals(lifecycleFingerprint)) {
                throw new AlertDeliveryConflictException("同一活动告警键收到不同内容；请先结束当前告警轮次");
            }
            if (current != null && !current.active() && current.lastState() == state
                    && !current.lastPayloadFingerprint().equals(lifecycleFingerprint)) {
                throw new AlertDeliveryConflictException("同一已结束告警键收到不同内容；请为新故障轮次使用新的 alertKey");
            }
            if (current != null && current.lastState() == state
                    && current.lastPayloadFingerprint().equals(lifecycleFingerprint)) {
                AlertDelivery receipt = new AlertDelivery(source, delivery, key, state, fingerprint, current.generation(),
                        current.sessionId(), current.incidentId(), current.taskId(), now);
                if (!tx.saveDelivery(receipt)) { throw new AlertDeliveryConflictException("重复告警投递，请重试查询原受理结果"); }
                return result(receipt, true);
            }
            long generation = current == null ? (state == AlertEventState.FIRING ? 1 : 0)
                    : current.generation() + (state == AlertEventState.FIRING ? 1 : 0);
            String sessionId = current == null ? null : current.sessionId();
            String incidentId = current == null ? null : current.incidentId();
            String taskId = current == null ? null : current.taskId();
            InvestigationTask createdTask = null;
            if (state == AlertEventState.FIRING) {
                String question = "告警自动调查：" + (message.isBlank() ? "请根据下方路径与窗口排查失败原因" : message);
                Session session = incidents.openSession(alertOwnerUserId == null ? source : alertOwnerUserId);
                sessionId = session.sessionId();
                Incident incident = incidents.openIncident(sessionId, IncidentOrigin.ALERT,
                        ResourceTarget.route(target), range, source);
                incidentId = incident.incidentId();
                try {
                    createdTask = register(sessionId, question);
                    createdTask.bind(incidentId, target, ResourceTarget.route(target), range);
                    incidents.attachTask(incidentId, createdTask.taskId());
                    InvestigationTask queuedTask = createdTask;
            tasks.enqueue(queuedTask, "ALERT", range, () -> runAlert(queuedTask));
                    taskId = createdTask.taskId();
                } catch (RuntimeException failure) {
                    if (createdTask != null) { tasks.discard(createdTask.taskId()); }
                    rollback(incident, session);
                    throw failure;
                }
            } else if (current != null && current.taskId() != null) {
                TaskView active = tasks.get(current.taskId());
                if (active != null && active.status().active()) { tasks.resolveAlert(current.taskId()); }
                if (current.incidentId() != null) {
                    incidents.resolveAlert(current.incidentId(), "告警已恢复（RESOLVED），原调查轮次已结束");
                }
            }
            AlertLifecycle next = new AlertLifecycle(source, key, state == AlertEventState.FIRING, target, generation,
                    sessionId, incidentId, taskId, state, lifecycleFingerprint, now);
            AlertDelivery receipt = new AlertDelivery(source, delivery, key, state, fingerprint, generation,
                    sessionId, incidentId, state == AlertEventState.RESOLVED ? null : taskId, now);
            if (!tx.saveDelivery(receipt)) { throw new AlertDeliveryConflictException("重复告警投递，请重试查询原受理结果"); }
            tx.saveLifecycle(next);
            return result(receipt, false);
            });
        }
        return result;
    }

    private AlertIngestResult result(AlertDelivery delivery, boolean duplicate) {
        TaskView task = delivery.taskId() == null ? null : tasks.get(delivery.taskId());
        return new AlertIngestResult(delivery.sessionId(), delivery.incidentId(), delivery.taskId(),
                task == null ? null : task.status(), duplicate, delivery.state() == AlertEventState.RESOLVED,
                delivery.generation());
    }

    private static String requiredIdentity(String value, String message) {
        if (value == null || value.isBlank()) { throw new IllegalArgumentException(message); }
        return bounded(value, 128, "sourceId");
    }

    private static String bounded(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value.trim();
    }

    private static String fingerprint(String... values) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private TaskView submit(String path, String question, IncidentOrigin origin, TimeRange timeRange,
                            String triggeredBy) {
        String target = validatePath(path);
        String asked = validateQuestion(question);
        access.require(alertOwnerUserId == null ? triggeredBy : alertOwnerUserId,
                com.rover.agent.core.port.AgentAccess.Permission.INVESTIGATE);
        Session session = incidents.openSession(alertOwnerUserId == null ? triggeredBy : alertOwnerUserId);
        Incident incident = incidents.openIncident(session.sessionId(), origin, ResourceTarget.route(target),
                timeRange, triggeredBy);
        try {
            InvestigationTask task = register(session.sessionId(), asked);
            task.bind(incident.incidentId(), target, ResourceTarget.route(target), incident.timeRange());
            incidents.attachTask(incident.incidentId(), task.taskId());
            tasks.enqueue(task, "ALERT", incident.timeRange(), () -> runAlert(task));
            return task.view();
        } catch (RejectedExecutionException ex) {
            rollback(incident, session);
            throw ex;
        }
    }

    /**
     * 登记一个待执行的调查任务：只落 PENDING 快照，不解析目标、不发起任何阻塞调用。
     *
     * @throws com.rover.agent.runtime.task.SessionTaskRunningException 同会话已有仍在执行的任务
     * @throws RejectedExecutionException                                任务登记容量已满
     */
    public InvestigationTask register(String sessionId, String question) {
        return tasks.register(sessionId, question);
    }

    private com.rover.agent.core.port.AgentAccess access = com.rover.agent.core.port.AgentAccess.local();
    public void access(com.rover.agent.core.port.AgentAccess access) { this.access = access; }

    /** 提交执行；队列满时抛 {@link RejectedExecutionException}，调用方应回滚登记并回 429。 */
    public void execute(Runnable runnable) {
        tasks.execute(runnable);
    }

    public void enqueue(InvestigationTask task, String kind, Object payload, Runnable fallback) {
        tasks.enqueue(task, kind, payload, fallback);
    }

    public void handler(String kind, java.util.function.BiConsumer<InvestigationTask,String> handler) { tasks.handler(kind, handler); }

    /** 提交执行失败时回滚登记，避免留下永远 PENDING 的任务。 */
    public void discard(String taskId) {
        tasks.discard(taskId);
    }

    /** 告警任务用绑定的事件、目标和时间窗口进入与人工会话相同的 ReAct 工具循环。 */
    private void runAlert(InvestigationTask task) {
        task.start();
        task.markRunning();
        try {
            if (access.managed()) {
                Session owner = incidents.session(task.sessionId()).orElseThrow(() -> new IllegalStateException("任务会话已删除"));
                if (!incidents.accepts(owner)) { throw new com.rover.agent.core.port.AgentAccess.Denied("任务资源范围已失效"); }
                access.require(owner.userId(), com.rover.agent.core.port.AgentAccess.Permission.INVESTIGATE);
            }
            // 执行前若已被取消（例如排队期间被取消），直接退出，不再发起任何取数。
            if (task.cancelled()) {
                return;
            }
            String journalBinding = journal.identity(task.path());
            toolLoop.run(task, "告警来源：" + String.valueOf(task.incidentId()));
            if (task.cancelled()) { return; }
            if (task.view().result() != null) { incidents.summarise(task.incidentId(), task.view().result().summary()); }
            journal.record(task.view(), journalBinding);
        } catch (Exception ex) {
            if (task.cancelled()) {
                // 取消过程中断：结论已被标记 CANCELLED，不要覆盖成失败。
                return;
            }
            log.error("告警 Agent 任务执行异常", ex);
            task.fail(task.budget().stopReason() == null ? "告警调查任务执行失败" : task.budget().stopReason());
        } finally {
            task.clearRunning();
        }
    }

    /** 取消一个仍在执行中的调查任务；任务不存在或已终态时返回 false。 */
    public boolean cancel(String taskId) {
        return tasks.cancel(taskId);
    }

    /** 撤销未成功提交的调查所留下的会话与事件。 */
    private void rollback(Incident incident, Session session) {
        incidents.removeIncident(incident.incidentId());
        incidents.removeSession(session.sessionId());
    }

    public TaskView get(String taskId) {
        return tasks.get(taskId);
    }

    /** 订阅任务事件，先发送快照再推送增量；任务不在执行登记表时返回空。 */
    public Optional<TaskEventSubscription> subscribeEvents(String taskId, TaskEventSubscriber subscriber) {
        InvestigationTask task = tasks.find(taskId);
        return task == null ? Optional.empty() : Optional.of(task.subscribe(subscriber));
    }

    private static String identity(String value) {
        String normalized = value == null || value.isBlank() ? null : value.trim();
        if (normalized != null && normalized.length() > 128) {
            throw new IllegalArgumentException("告警来源或处理账号不能超过 128 字");
        }
        return normalized;
    }

    private static String validatePath(String path) {
        String target = path == null ? null : path.trim();
        if (target == null || target.isBlank() || target.length() > MAX_PATH_LENGTH || !target.startsWith("/")
                || target.startsWith("//") || target.indexOf('?') >= 0 || target.indexOf('#') >= 0
                || target.chars().anyMatch(ch -> Character.isWhitespace(ch) || Character.isISOControl(ch))) {
            throw new IllegalArgumentException("请输入不含查询参数的请求路径");
        }
        return target;
    }

    private static String validateQuestion(String question) {
        if (question == null || question.isBlank() || question.length() > MAX_QUESTION_LENGTH) {
            throw new IllegalArgumentException("请输入不超过 1000 字的问题");
        }
        return question.trim();
    }
}
