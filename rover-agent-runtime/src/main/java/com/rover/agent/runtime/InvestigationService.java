package com.rover.agent.runtime;

import com.rover.agent.core.capability.CapabilityExecutor;
import com.rover.agent.core.capability.CapabilityRegistry;
import com.rover.agent.core.event.TaskEventSubscriber;
import com.rover.agent.core.event.TaskEventSubscription;
import com.rover.agent.core.model.AgentStepType;
import com.rover.agent.core.model.AlertDelivery;
import com.rover.agent.core.model.AlertEventState;
import com.rover.agent.core.model.AlertIngestResult;
import com.rover.agent.core.model.AlertLifecycle;
import com.rover.agent.core.model.Incident;
import com.rover.agent.core.model.IncidentOrigin;
import com.rover.agent.core.model.InvestigationReport;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.StepStatus;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.model.TimeRange;
import com.rover.agent.core.planning.InvestigationPlanner;
import com.rover.agent.core.planning.PlanValidator;
import com.rover.agent.core.planning.PlanningLimits;
import com.rover.agent.core.planning.RuleBasedPlanner;
import com.rover.agent.core.repository.AlertLifecycleRepository;
import com.rover.agent.core.port.ConfigReadPort;
import com.rover.agent.core.port.EventReadPort;
import com.rover.agent.core.port.InstanceReadPort;
import com.rover.agent.core.port.KnowledgeReadPort;
import com.rover.agent.core.port.LogQueryPort;
import com.rover.agent.core.port.MetricReadPort;
import com.rover.agent.core.port.RouteReadPort;
import com.rover.agent.core.port.TraceReadPort;
import com.rover.agent.runtime.graph.DynamicInvestigationGraph;
import com.rover.agent.runtime.journal.OpsJournal;
import com.rover.agent.runtime.graph.InvestigationOutcome;
import com.rover.agent.runtime.action.AgentActionService;
import com.rover.agent.runtime.action.EvidenceGatedPlanProposer;
import com.rover.agent.runtime.llm.ModelExplainer;
import com.rover.agent.runtime.task.IncidentRegistry;
import com.rover.agent.runtime.task.InvestigationTask;
import com.rover.agent.runtime.task.InvestigationTaskRegistry;
import com.rover.agent.runtime.task.AlertDeliveryConflictException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 机器事件的只读调查入口：register 登记任务，run 在 Worker 中执行调查与解读。
 * 事实来自只读端口；模型不可用时保留规则结论，任务状态通过仓储保存。
 */
public final class InvestigationService {

    private static final Logger log = LoggerFactory.getLogger(InvestigationService.class);
    private static final int MAX_PATH_LENGTH = 512;
    private static final int MAX_QUESTION_LENGTH = 1000;
    private static final long ALERT_DELIVERY_RETENTION_MILLIS = java.time.Duration.ofDays(90).toMillis();

    /** 默认能力注册表：纯数据且不可变，默认装配与规划器共用同一份，避免口径分叉。 */
    private static final CapabilityRegistry STANDARD_CAPABILITIES = CapabilityRegistry.standard();

    private static final String STEP_AI = "AI 解读";
    private static final String AI_RUNNING = "根据只读快照与调查结论生成解释";
    private static final String AI_COMPLETED = "已生成解释";
    private static final String AI_FAILED = "模型暂时不可用，规则诊断已保留";
    private static final String AI_UNAVAILABLE = "模型暂时不可用，当前展示规则诊断。";
    private static final String AI_NOT_CONFIGURED = "尚未配置模型，当前展示规则诊断。";

    private final IncidentRegistry incidents;
    private final InvestigationTaskRegistry tasks;
    private final ModelExplainer explainer;
    private final InvestigationPlanner planner;
    private final PlanValidator validator;
    private final CapabilityExecutor executor;
    private final PlanningLimits limits;
    private final OpsJournal journal;
    private final String alertOwnerUserId;
    private final AgentActionService actions;
    private final AlertLifecycleRepository alertLifecycles;

    /** 默认装配规则规划器、标准能力注册表和规划上限。 */
    public InvestigationService(RouteReadPort routes, InstanceReadPort instances, MetricReadPort metrics,
                                TraceReadPort traces, ConfigReadPort configs, EventReadPort events,
                                LogQueryPort logs, KnowledgeReadPort knowledge,
                                IncidentRegistry incidents, InvestigationTaskRegistry tasks,
                                ModelExplainer explainer) {
        this(routes, instances, metrics, traces, configs, events, logs, knowledge, incidents, tasks, explainer, null);
    }

    /** 端口装配的告警入口；允许宿主接入受权限检查的待审批提案服务。 */
    public InvestigationService(RouteReadPort routes, InstanceReadPort instances, MetricReadPort metrics,
                                TraceReadPort traces, ConfigReadPort configs, EventReadPort events,
                                LogQueryPort logs, KnowledgeReadPort knowledge,
                                IncidentRegistry incidents, InvestigationTaskRegistry tasks,
                                ModelExplainer explainer, AgentActionService actions) {
        this(incidents, tasks, explainer,
                new RuleBasedPlanner(STANDARD_CAPABILITIES),
                new PlanValidator(STANDARD_CAPABILITIES, PlanningLimits.defaults()),
                new CapabilityExecutor(routes, instances, metrics, traces, configs, events, logs, knowledge,
                        STANDARD_CAPABILITIES, taskId -> { }, taskId -> contextForTask(incidents, tasks, taskId)),
                PlanningLimits.defaults(), OpsJournal.none(), null, actions);
    }

    /** 注入规划器、校验器、能力执行器和规划上限；取数统一经 CapabilityExecutor。 */
    public InvestigationService(IncidentRegistry incidents, InvestigationTaskRegistry tasks,
                                ModelExplainer explainer, InvestigationPlanner planner, PlanValidator validator,
                                CapabilityExecutor executor, PlanningLimits limits) {
        this(incidents, tasks, explainer, planner, validator, executor, limits, OpsJournal.none());
    }

    /** 完整装配。调查结束后，有已确认根因才写入资源笔记。 */
    public InvestigationService(IncidentRegistry incidents, InvestigationTaskRegistry tasks,
                                ModelExplainer explainer, InvestigationPlanner planner, PlanValidator validator,
                                CapabilityExecutor executor, PlanningLimits limits, OpsJournal journal) {
        this(incidents, tasks, explainer, planner, validator, executor, limits, journal, null);
    }

    /** 告警处理人由服务端配置；未配置时归属认证来源主体，事件仍独立保存来源。 */
    public InvestigationService(IncidentRegistry incidents, InvestigationTaskRegistry tasks,
                                ModelExplainer explainer, InvestigationPlanner planner, PlanValidator validator,
                                CapabilityExecutor executor, PlanningLimits limits, OpsJournal journal,
                                String alertOwnerUserId) {
        this(incidents, tasks, explainer, planner, validator, executor, limits, journal, alertOwnerUserId, null);
    }

    /** 完整装配；候选计划沿用调查会话归属与服务端提案权限。 */
    public InvestigationService(IncidentRegistry incidents, InvestigationTaskRegistry tasks,
                                ModelExplainer explainer, InvestigationPlanner planner, PlanValidator validator,
                                CapabilityExecutor executor, PlanningLimits limits, OpsJournal journal,
                                String alertOwnerUserId, AgentActionService actions) {
        this(incidents, tasks, explainer, planner, validator, executor, limits, journal, alertOwnerUserId, actions, null);
    }

    public InvestigationService(IncidentRegistry incidents, InvestigationTaskRegistry tasks,
                                ModelExplainer explainer, InvestigationPlanner planner, PlanValidator validator,
                                CapabilityExecutor executor, PlanningLimits limits, OpsJournal journal,
                                String alertOwnerUserId, AgentActionService actions,
                                AlertLifecycleRepository alertLifecycles) {
        this.incidents = incidents;
        this.tasks = tasks;
        this.explainer = explainer;
        this.planner = planner;
        this.validator = validator;
        this.executor = executor;
        this.limits = limits == null ? PlanningLimits.defaults() : limits;
        this.journal = journal == null ? OpsJournal.none() : journal;
        this.alertOwnerUserId = identity(alertOwnerUserId);
        this.actions = actions;
        this.alertLifecycles = alertLifecycles;
        tasks.handler("ALERT", (task, payload) -> {
            try {
                TimeRange range = new com.fasterxml.jackson.databind.ObjectMapper()
                        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).readValue(payload, TimeRange.class);
                task.bind(task.incidentId(), task.path(), task.target(), range);
                run(task);
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
                    tasks.enqueue(queuedTask, "ALERT", range, () -> run(queuedTask));
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
            tasks.enqueue(task, "ALERT", incident.timeRange(), () -> run(task));
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

    /** 执行一次已解析目标的调查；不回调结论，行为与旧入口一致。 */
    public void run(InvestigationTask task) {
        run(task, null);
    }

    /**
     * 在 Worker 中执行调查、规则结论、模型解读和事件回写；任务须先绑定目标。
     * onReport 在任务终态事件发布前执行，保证完成时能读到结论。
     */
    public void run(InvestigationTask task, Consumer<InvestigationReport> onReport) {
        task.start();
        task.markRunning();
        try {
            if (access.managed()) {
                Session owner = incidents.session(task.sessionId()).orElseThrow(() -> new IllegalStateException("任务会话已删除"));
                if (!incidents.accepts(owner)) { throw new com.rover.agent.core.port.AgentAccess.Denied("任务资源范围已失效"); }
                access.require(owner.userId(), com.rover.agent.core.port.AgentAccess.Permission.INVESTIGATE);
            }
            if ((task.resumeRounds() >= limits.maxRounds() || task.resumeToolCalls() >= limits.maxToolCalls())
                    && task.resumePlan().progress().facts() == null) {
                task.fail("恢复点已用尽规划或工具预算，保留现有证据，请人工复核");
                return;
            }
            // 执行前若已被取消（例如排队期间被取消），直接退出，不再发起任何取数。
            if (task.cancelled()) {
                return;
            }
            DynamicInvestigationGraph graph =
                    new DynamicInvestigationGraph(planner, validator, executor, limits, task);
            String journalBinding = journal.identity(task.path());
            InvestigationOutcome outcome = graph.investigate(task.taskId(), task.path(), task.question(),
                    task.target(), task.timeRange());
            if (task.cancelled()) {
                return;
            }
            if (outcome.needsClarification()) {
                // 规划认为继续调查缺少必要信息：停在澄清点，而不是硬凑一个没有依据的结论。
                task.waitForInput(outcome.clarification());
                return;
            }
            InvestigationReport report = new InvestigationReport(
                    outcome.findings().summary(), outcome.findings().confidence(), outcome.evidence(),
                    outcome.limitations(), outcome.findings().hypotheses(), null);
            report = explain(task, report, outcome);
            if (task.cancelled()) {
                return;
            }
            var decision = EvidenceGatedPlanProposer.propose(task, task.path(), outcome.findings(),
                    outcome.evidence(), outcome.limitations(), false, actions);
            task.step(AgentStepType.ACTION_PROPOSAL, "处置计划准入",
                    decision.evidenceQualified() && !decision.created() ? StepStatus.FAILED : StepStatus.COMPLETED,
                    decision.detail());
            if (!decision.created()) {
                List<String> limitations = new ArrayList<>(report.limitations());
                limitations.add("处置计划未生成：" + decision.detail());
                report = new InvestigationReport(report.summary(), report.confidence(), report.evidence(),
                        List.copyOf(limitations), report.hypotheses(), report.aiAnalysis());
            }
            publish(onReport, report);
            task.complete(report);
            // 结论回写到事件：事件因此成为「一个问题的多次调查」的聚合点，追问时能继承最新结论。
            incidents.summarise(task.incidentId(), report.summary());
            journal.record(task.view(), journalBinding);
        } catch (Exception ex) {
            if (task.cancelled()) {
                // 取消过程中断：结论已被标记 CANCELLED，不要覆盖成失败。
                return;
            }
            log.error("Agent 诊断任务异常", ex);
            task.fail(task.budget().stopReason() == null ? "诊断任务执行失败" : task.budget().stopReason());
        } finally {
            task.clearRunning();
        }
    }

    /** 取消一个仍在执行中的调查任务；任务不存在或已终态时返回 false。 */
    public boolean cancel(String taskId) {
        return tasks.cancel(taskId);
    }

    /** 结论回调失败只损失可追溯性，不该把一次已经跑完的调查判成失败。 */
    private static void publish(Consumer<InvestigationReport> onReport, InvestigationReport report) {
        if (onReport == null) {
            return;
        }
        try {
            onReport.accept(report);
        } catch (RuntimeException ex) {
            log.warn("调查结论落库失败，不影响本次调查结果", ex);
        }
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

    private InvestigationReport explain(InvestigationTask task, InvestigationReport report,
                                        InvestigationOutcome outcome) {
        if (!explainer.configured()) {
            return withoutAnalysis(report, AI_NOT_CONFIGURED);
        }
        if (!explainer.available()) {
            return withoutAnalysis(report, AI_UNAVAILABLE);
        }
        try {
            task.step(AgentStepType.AI_EXPLANATION, STEP_AI, StepStatus.RUNNING, AI_RUNNING);
            String question = task.timeRange().isUnspecified() ? task.question()
                    : task.question() + "\n指定调查时间范围：" + task.timeRange().describe();
            ModelExplainer.Explanation explanation = explainer.explainStreaming(task.path(), question,
                    outcome.evidence(), outcome.findings().hypotheses(), task::appendAnalysis, task::appendThinking);
            // 步骤里区分「运行时预读」与「模型另调」：解释的依据可追溯到具体快照，而不是一句「已生成解释」。
            task.step(AgentStepType.AI_EXPLANATION, STEP_AI, StepStatus.COMPLETED, completedDetail(explanation));
            return new InvestigationReport(report.summary(), report.confidence(), report.evidence(),
                    report.limitations(), report.hypotheses(), explanation.text().trim());
        } catch (Exception ex) {
            log.warn("Agent 模型解读失败（{}）",com.rover.agent.runtime.llm.ModelFailures.kind(ex));
            task.step(AgentStepType.AI_EXPLANATION, STEP_AI, StepStatus.FAILED, AI_FAILED);
            return withoutAnalysis(report, AI_UNAVAILABLE);
        }
    }

    /** 解读成功的结果说明：如实区分运行时预读的快照与模型自己额外调用的工具。 */
    private static String completedDetail(ModelExplainer.Explanation explanation) {
        StringBuilder detail = new StringBuilder(AI_COMPLETED)
                .append("，运行时预读快照：").append(String.join("、", explanation.prefetched()));
        if (!explanation.tools().isEmpty()) {
            detail.append("；模型另调工具：").append(String.join("、", explanation.tools()));
        }
        return detail.toString();
    }

    private static InvestigationReport withoutAnalysis(InvestigationReport report, String limitation) {
        List<String> limitations = new ArrayList<>(report.limitations());
        limitations.add(limitation);
        return new InvestigationReport(report.summary(), report.confidence(), report.evidence(),
                List.copyOf(limitations), report.hypotheses(), null);
    }

    private static String identity(String value) {
        String normalized = value == null || value.isBlank() ? null : value.trim();
        if (normalized != null && normalized.length() > 128) {
            throw new IllegalArgumentException("告警来源或处理账号不能超过 128 字");
        }
        return normalized;
    }

    private static com.rover.agent.core.model.InvestigationContext contextForTask(
            IncidentRegistry incidents, InvestigationTaskRegistry tasks, String taskId) {
        InvestigationTask task = tasks.find(taskId);
        if (task == null) {
            return com.rover.agent.core.model.InvestigationContext.legacy(taskId);
        }
        Session session = incidents.session(task.sessionId()).orElse(null);
        return new com.rover.agent.core.model.InvestigationContext(task.taskId(), task.incidentId(),
                session == null ? null : session.scope(), null,
                session == null ? null : session.targetNamespace(), task.target(), task.timeRange(),
                java.util.Map.of(), "LOCAL_V1");
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
