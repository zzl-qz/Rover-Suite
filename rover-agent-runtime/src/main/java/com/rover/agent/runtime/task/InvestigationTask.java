package com.rover.agent.runtime.task;

import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.event.TaskEvent;
import com.rover.agent.core.event.TaskEventSubscriber;
import com.rover.agent.core.event.TaskEventSubscription;
import com.rover.agent.core.event.TaskEventType;
import com.rover.agent.core.event.TaskSnapshot;
import com.rover.agent.core.model.ActionPlan;
import com.rover.agent.core.model.AgentStepType;
import com.rover.agent.core.model.IntentDecision;
import com.rover.agent.core.model.InvestigationReport;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.Step;
import com.rover.agent.core.model.StepStatus;
import com.rover.agent.core.model.TaskStatus;
import com.rover.agent.core.model.TaskType;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.planning.InvestigationPlan;
import com.rover.agent.runtime.graph.InvestigationReporter;
import com.rover.agent.runtime.metrics.AgentMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 一次调查任务的运行状态；状态变更、快照落库与事件发布都在同步块内完成，保证观察者看到的
 * 顺序与真实状态变化一致。
 *
 * 任务在登记时只有会话与问题——「调查哪个对象」由执行线程在目标解析阶段确定（{@link #bind}），
 * 解析不出对象则停在 {@link TaskStatus#WAITING_INPUT}（{@link #waitForInput}）。
 * 因此快照里的目标、路径与事件 ID 在执行开始前是空的，这是刻意的：登记不阻塞 Servlet 线程。
 *
 * 每次状态变更做两件事：把最新快照推给 {@code snapshotSink}（由登记方接到任务记录存储上），
 * 以及向事件总线发布一条结构化事件。前者是事实来源，后者只是通知——发布本身不做网络 IO，
 * 订阅者掉队或断开都不会影响本对象的执行。
 */
public final class InvestigationTask implements InvestigationReporter {

    private final String taskId;
    private final String sessionId;
    private final String question;
    private final Consumer<TaskView> snapshotSink;
    private final TaskEventBus events;
    private final AgentMetrics metrics;
    private final long createdAtMillis = System.currentTimeMillis();
    private final List<Step> steps = new ArrayList<>();
    private final StringBuilder analysis = new StringBuilder();
    /** 模型思考增量缓冲：与解读分开存放，前端要把两者放在不同位置。 */
    private final StringBuilder thinking = new StringBuilder();
    private final List<AgentCapability> executedCapabilities = new ArrayList<>();
    private TaskStatus status = TaskStatus.PENDING;
    private AgentStepType currentStage;
    private String incidentId;
    private String path = "";
    private ResourceTarget target = ResourceTarget.unknown();
    private TaskType taskType = TaskType.INVESTIGATION;
    private IntentDecision intent;
    private InvestigationPlan plan = InvestigationPlan.empty();
    private ActionPlan actionPlan;
    private long completedAtMillis;
    private InvestigationReport report;
    private String error;
    private String clarification;
    private long eventSeq;
    private boolean settled;

    InvestigationTask(String taskId, String sessionId, String question, Consumer<TaskView> snapshotSink,
                      TaskEventBus events, AgentMetrics metrics) {
        this.taskId = taskId;
        this.sessionId = sessionId;
        this.question = question;
        this.snapshotSink = snapshotSink;
        this.events = events;
        this.metrics = metrics == null ? AgentMetrics.NOOP : metrics;
    }

    public String taskId() {
        return taskId;
    }

    public String sessionId() {
        return sessionId;
    }

    /** 任务所属事件；目标解析完成前为 {@code null}。 */
    public synchronized String incidentId() {
        return incidentId;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    /** 被调查的请求路径；目标解析完成前为空串。 */
    public synchronized String path() {
        return path;
    }

    /** 本次任务的结构化目标；目标解析完成前为未知对象。 */
    public synchronized ResourceTarget target() {
        return target;
    }

    /** 本次任务识别出的意图；尚未识别时为 {@code null}。 */
    public synchronized IntentDecision intent() {
        return intent;
    }

    /** 本次任务类型；未识别意图前默认为故障调查。 */
    public synchronized TaskType taskType() {
        return taskType;
    }

    public String question() {
        return question;
    }

    /** 任务已登记为 PENDING：事件在实际运行前发布，订阅者通常靠快照而非它来认识任务。 */
    synchronized void announceCreated() {
        publish(TaskEventType.TASK_CREATED, Map.of("status", status));
    }

    /**
     * 目标解析完成：绑定事件、取数路径与结构化对象。
     *
     * 由执行线程在解析出对象后调用；在此之前快照里的这些字段保持空值。
     */
    public synchronized void bind(String incidentId, String path, ResourceTarget target) {
        this.incidentId = incidentId;
        this.path = path == null ? "" : path;
        this.target = target == null ? ResourceTarget.unknown() : target;
        persist();
    }

    /**
     * 意图识别完成：把任务类型与意图判断写进快照。
     *
     * 意图不是一次状态迁移，而是结构化进展；用一次全量快照事件通知订阅者，
     * 前端按「快照覆盖」语义合并，既能看到意图判定，也不会打乱步骤与解读的增量流。
     */
    public synchronized void classify(TaskType type, IntentDecision decision) {
        this.taskType = type == null ? TaskType.INVESTIGATION : type;
        this.intent = decision;
        persist();
        publishSnapshot();
    }

    /**
     * 本轮调查计划：用户要能看到 Agent 打算查哪几项、每步依据是什么。
     *
     * 计划随快照暴露（不是过程日志），因此这里与状态变更走同一条持久化路径。
     */
    @Override
    public synchronized void reportPlan(InvestigationPlan reported) {
        this.plan = reported == null ? InvestigationPlan.empty() : reported;
        persist();
        publishSnapshot();
    }

    /** 某个能力已被执行或按事实跳过：重复出现只记一次，顺序按首次使用。 */
    @Override
    public synchronized void reportCapabilityExecuted(AgentCapability capability) {
        if (capability != null && !executedCapabilities.contains(capability)) {
            executedCapabilities.add(capability);
        }
        persist();
        publishSnapshot();
    }

    /** 处置请求的产出：只生成计划、不执行任何写操作，说明书随快照一起展示。 */
    public synchronized void attachActionPlan(ActionPlan plan) {
        this.actionPlan = plan;
        persist();
        publishSnapshot();
    }

    /** 计划与能力进展的全量快照通知：payload 与订阅时补发的快照同型，订阅者按覆盖语义处理。 */
    private void publishSnapshot() {
        publish(TaskEventType.SNAPSHOT, snapshot());
    }

    /**
     * 停在澄清点：目标无法确定，等待用户补充信息。
     *
     * 澄清提问写进快照，前端据此在任务卡上提问；任务不占用会话并发位，用户可以继续追问。
     */
    public synchronized void waitForInput(String clarification) {
        this.clarification = clarification;
        this.completedAtMillis = System.currentTimeMillis();
        this.status = TaskStatus.WAITING_INPUT;
        persist();
        publish(TaskEventType.CLARIFICATION_REQUIRED, Map.of("clarification", clarification));
        settle();
    }

    /**
     * 调查开始执行。
     *
     * 幂等：只有登记后仍是 PENDING 的任务才会进入 RUNNING。编排层与各用例服务都可能调用它
     * （轻量用例自带执行段），重复调用不应重复发事件或把已落定的任务拉回运行态。
     */
    public synchronized void start() {
        if (status != TaskStatus.PENDING) {
            return;
        }
        this.status = TaskStatus.RUNNING;
        persist();
        publish(TaskEventType.TASK_STARTED, Map.of("status", status));
        metrics.taskStarted();
    }

    /**
     * 记录一步调查进度：同名且仍在执行中的步骤被就地更新，避免同一阶段出现重复行。
     *
     * {@code detail} 的含义随状态而定：执行中作为输入说明，终态作为结果说明；失败时作为失败原因。
     * 就地更新时沿用首次上报的时刻作为开始时间，不伪造时间戳。
     */
    @Override
    public synchronized void step(AgentStepType type, String name, StepStatus status, String detail) {
        this.currentStage = type;
        if (status != StepStatus.RUNNING) {
            for (int i = steps.size() - 1; i >= 0; i--) {
                Step previous = steps.get(i);
                if (name.equals(previous.name()) && previous.status() == StepStatus.RUNNING) {
                    Step finished = finish(previous, type, status, detail);
                    steps.set(i, finished);
                    persist();
                    publish(stepEventType(status), Map.of("step", finished));
                    return;
                }
            }
        }
        long now = System.currentTimeMillis();
        Step recorded = status == StepStatus.RUNNING
                ? new Step(UUID.randomUUID().toString(), taskId, type, name, status, detail, null, now, 0L, null)
                : new Step(UUID.randomUUID().toString(), taskId, type, name, status, null,
                        status == StepStatus.FAILED ? null : detail, now, now,
                        status == StepStatus.FAILED ? detail : null);
        steps.add(recorded);
        persist();
        publish(stepEventType(status), Map.of("step", recorded));
    }

    /** 把执行中的步骤就地改成终态：沿用原开始时刻，只补结束信息。 */
    private static Step finish(Step previous, AgentStepType type, StepStatus status, String detail) {
        return new Step(previous.stepId(), previous.taskId(), type, previous.name(), status, previous.inputSummary(),
                status == StepStatus.FAILED ? null : detail, previous.startedAtMillis(), System.currentTimeMillis(),
                status == StepStatus.FAILED ? detail : null);
    }

    private static TaskEventType stepEventType(StepStatus status) {
        return switch (status) {
            case RUNNING -> TaskEventType.STEP_STARTED;
            case FAILED -> TaskEventType.STEP_FAILED;
            default -> TaskEventType.STEP_COMPLETED;
        };
    }

    public synchronized void complete(InvestigationReport report) {
        this.report = report;
        this.completedAtMillis = System.currentTimeMillis();
        this.status = TaskStatus.COMPLETED;
        persist();
        // 证据与结论同时落定：事件给观察者一个明确的「证据已产出」通知，内容与快照完全一致。
        publish(TaskEventType.EVIDENCE_ADDED, Map.of("evidence", report.evidence(),
                "count", report.evidence().size()));
        publish(TaskEventType.TASK_COMPLETED, Map.of("status", status, "summary", report.summary(),
                "confidence", report.confidence()));
        settle();
    }

    public synchronized void fail(String error) {
        this.error = error;
        this.completedAtMillis = System.currentTimeMillis();
        this.status = TaskStatus.FAILED;
        persist();
        publish(TaskEventType.TASK_FAILED, Map.of("status", status, "error", error));
        settle();
    }

    /**
     * 任务落定：只上报一次耗时与终态，供指标统计。
     *
     * 状态机保证这三个终态互斥（澄清、完成、失败），但仍用标志位兜底，
     * 避免重复调用把活跃数减成负数、或让同一任务计入两次耗时。
     */
    private void settle() {
        if (settled) {
            return;
        }
        settled = true;
        metrics.taskSettled(status.name(), Math.max(0L, completedAtMillis - createdAtMillis));
    }

    /** 推送模型解读增量：先落缓冲再发布事件，晚连上的订阅者才能靠快照对齐前缀。 */
    public synchronized void appendAnalysis(String chunk) {
        if (chunk == null || chunk.isEmpty() || status.terminal()) {
            return;
        }
        analysis.append(chunk);
        publish(TaskEventType.ANALYSIS_DELTA, Map.of("text", chunk));
    }

    /**
     * 推送模型思考增量：与解读增量同构，但走独立缓冲与事件。
     *
     * 分开是刻意的——思考与答案是两段不同内容，界面上也占两个位置（思考收在折叠面板里，
     * 答案才是正文），混成一条流会让前端不得不自己判断哪一段是思考。
     */
    public synchronized void appendThinking(String chunk) {
        if (chunk == null || chunk.isEmpty() || status.terminal()) {
            return;
        }
        thinking.append(chunk);
        publish(TaskEventType.THINKING_DELTA, Map.of("text", chunk));
    }

    /**
     * 订阅任务事件：先补发全量快照（含已产生的解读文本），再按增量投递。
     *
     * 快照读取与订阅登记在同一同步块内完成，因此增量既不会漏发也不会重复；
     * 事件写往订阅者队列是非阻塞的，真正的网络 IO 由 {@link TaskEventBus} 的派发线程负责。
     */
    public synchronized TaskEventSubscription subscribe(TaskEventSubscriber subscriber) {
        return events.subscribe(taskId, this::snapshot, subscriber);
    }

    public synchronized boolean terminal() {
        return status.terminal();
    }

    public synchronized TaskView view() {
        return new TaskView(taskId, sessionId, incidentId, status, currentStage, path, target, question,
                createdAtMillis, completedAtMillis, List.copyOf(steps), report, error, clarification,
                taskType, intent, plan, List.copyOf(executedCapabilities), actionPlan);
    }

    /** 任务快照：视图 + 已产生的解读与思考全文 + 已发布的最新事件序号（订阅者的对齐依据）。 */
    private synchronized TaskSnapshot snapshot() {
        return new TaskSnapshot(view(), analysis.toString(), eventSeq, thinking.toString());
    }

    /** 把最新快照推给任务记录存储；调研期间读到的始终是一致视图。 */
    private void persist() {
        snapshotSink.accept(view());
    }

    /** 发布事件：只在状态锁内做非阻塞入队，事件序号在同一把锁内递增，顺序与状态变更一致。 */
    private void publish(TaskEventType type, Object payload) {
        events.publish(new TaskEvent(++eventSeq, taskId, type, System.currentTimeMillis(), payload));
    }
}