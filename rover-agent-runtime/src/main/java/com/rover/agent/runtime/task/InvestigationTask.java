package com.rover.agent.runtime.task;

import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.event.TaskEvent;
import com.rover.agent.core.event.TaskEventSubscriber;
import com.rover.agent.core.event.TaskEventSubscription;
import com.rover.agent.core.event.TaskEventType;
import com.rover.agent.core.event.TaskSnapshot;
import com.rover.agent.core.model.AgentCheckpoint;
import com.rover.agent.core.model.AgentStepType;
import com.rover.agent.core.model.CheckpointStage;
import com.rover.agent.core.model.Confidence;
import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.InvestigationReport;
import com.rover.agent.core.model.RecallChoice;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.Step;
import com.rover.agent.core.model.StepStatus;
import com.rover.agent.core.model.TaskStatus;
import com.rover.agent.core.model.TaskType;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.planning.InvestigationPlan;
import com.rover.agent.core.repository.AgentCheckpointRepository;
import com.rover.agent.runtime.graph.InvestigationReporter;
import com.rover.agent.runtime.metrics.AgentMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
    private final AgentCheckpointRepository checkpoints;
    private final long createdAtMillis = System.currentTimeMillis();
    private final List<Step> steps = new ArrayList<>();
    /** 本次执行已取到的证据：逐次交付、随恢复点落库，不等结论合成。 */
    private final List<Evidence> evidence = new ArrayList<>();
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
    private InvestigationPlan plan = InvestigationPlan.empty();
    private long completedAtMillis;
    private InvestigationReport report;
    private String error;
    private String clarification;
    private List<RecallChoice> recalls = List.of();
    /** 恢复点序号：每个任务内从 1 递增，用于回溯"这次调查经历过哪些安全点"。 */
    private int checkpointSeq;
    /** 最近一次恢复点上报的轮数与调用次数：STARTED / COMPLETED 这类生命周期恢复点沿用它们，不把计数清零。 */
    private int lastRoundNo;
    private int lastToolCallCount;
    private long eventSeq;
    private boolean settled;
    /** 协作式取消标志：执行线程在每个检查点轮询它，置位后不再产出结论。volatile 保证跨线程可见。 */
    private volatile boolean cancelled;
    /** 当前执行该任务的线程；取消时用于中断阻塞中的取数/模型调用（不参与快照，故 transient）。 */
    private transient Thread runner;
    private final AgentRunBudget budget;
    private final ScheduledExecutorService deadlines;
    private ScheduledFuture<?> deadlineTimer;

    InvestigationTask(String taskId, String sessionId, String question, Consumer<TaskView> snapshotSink,
                      TaskEventBus events, AgentMetrics metrics, AgentCheckpointRepository checkpoints) {
        this(taskId, sessionId, question, snapshotSink, events, metrics, checkpoints,
                AgentRunLimits.defaults(), null);
    }

    InvestigationTask(String taskId, String sessionId, String question, Consumer<TaskView> snapshotSink,
                      TaskEventBus events, AgentMetrics metrics, AgentCheckpointRepository checkpoints,
                      AgentRunLimits limits, ScheduledExecutorService deadlines) {
        this.taskId = taskId;
        this.sessionId = sessionId;
        this.question = question;
        this.snapshotSink = snapshotSink;
        this.events = events;
        this.metrics = metrics == null ? AgentMetrics.NOOP : metrics;
        this.checkpoints = checkpoints;
        this.budget = new AgentRunBudget(limits);
        this.deadlines = deadlines;
    }

    public AgentRunBudget budget() {
        return budget;
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

    /** 本次任务类型：人工会话为智能问答，机器事件触发的调查为自动调查。 */
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
     * 标明本次任务走的是人工对话主路径（{@link TaskType#CONVERSATION}）。
     *
     * 任务形态不是一次状态迁移，而是结构化事实；用一次全量快照事件通知订阅者，
     * 前端按「快照覆盖」语义合并，既能看到形态，也不会打乱步骤与解读的增量流。
     *
     * 这里刻意不再写任何「意图判断」：对话主路径上不存在预分类——查什么、怎么答由模型在对话中决定。
     */
    public synchronized void markConversation() {
        this.taskType = TaskType.CONVERSATION;
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

    /**
     * 交付本次执行新取到的证据：只追加，不覆盖。
     *
     * 证据随下一个安全恢复点落库，因此工具一返回、恢复点一推进，事实就已经在库里了；
     * 结论合成阶段不再"补写历史"。
     */
    @Override
    public synchronized void recordEvidence(List<Evidence> collected) {
        if (collected == null || collected.isEmpty()) {
            return;
        }
        evidence.addAll(collected);
    }

    /**
     * 推进一个安全恢复点。
     *
     * <p>调用方必须先完成状态变更（步骤已终态、证据已交付）：一次恢复点写入会把当前任务快照、
     * 证据与恢复点一起落库，恢复点表达的是「截至这里，一切已确定」，而不是「准备干到这里」。
     */
    @Override
    public synchronized void checkpoint(CheckpointStage stage, int roundNo, int toolCallCount, String runtimeNode) {
        if (stage == null) {
            return;
        }
        this.lastRoundNo = roundNo;
        this.lastToolCallCount = toolCallCount;
        AgentCheckpoint mark = new AgentCheckpoint(UUID.randomUUID().toString(), taskId, ++checkpointSeq, stage,
                roundNo, toolCallCount, steps.size(), evidence.size(), "", runtimeNode,
                System.currentTimeMillis());
        // 这里写的是当前快照本身：状态、步骤、证据与恢复点在同一次写入里落下，恢复点不会领先于事实。
        checkpoints.save(view(), mark);
    }

    /** 补齐结论里带来、但没经 {@link #recordEvidence} 交付过的证据：按 ID 去重，保证不丢也不重复。 */
    private void mergeEvidence(List<Evidence> reported) {
        if (reported == null || reported.isEmpty()) {
            return;
        }
        for (Evidence item : reported) {
            boolean known = evidence.stream().anyMatch(kept -> kept.evidenceId().equals(item.evidenceId()));
            if (!known) {
                evidence.add(item);
            }
        }
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
        if (status.terminal() || cancelled()) {
            return;
        }
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
        // 安全恢复点：此刻起「任务在跑」这件事已经落库，重启后据此知道它跑到过哪一步。
        checkpoint(CheckpointStage.STARTED, lastRoundNo, lastToolCallCount, null);
    }

    /** 协作式取消的判据：执行线程据此在检查点停下。volatile 读，允许非同步快速轮询。 */
    public boolean cancelled() {
        // 模型调用方可能把异常降级为规则结果；预算耗尽仍然必须结束整个任务。
        if (!cancelled && budget.stopReason() != null) {
            fail(budget.stopReason());
            return true;
        }
        return cancelled;
    }

    /**
     * 协作式取消：只接受仍在执行中的任务（PENDING / RUNNING）。
     *
     * 标记取消后把状态置为 {@link TaskStatus#CANCELLED}、发布 {@link TaskEventType#TASK_CANCELLED} 并落定指标；
     * 执行线程看到 {@link #cancelled()} 后应在下一个检查点停下，不再产出结论。已终态或已取消的任务返回 false。
     */
    public boolean cancel(String reason) {
        synchronized (this) {
            if (status != TaskStatus.PENDING && status != TaskStatus.RUNNING) {
                return false;
            }
            cancelled = true;
            this.completedAtMillis = System.currentTimeMillis();
            this.status = TaskStatus.CANCELLED;
            persist();
            publish(TaskEventType.TASK_CANCELLED, Map.of("status", status,
                    "reason", reason == null || reason.isBlank() ? "任务已被用户取消" : reason));
            settle();
        }
        budget.stop(reason == null || reason.isBlank() ? "任务已被用户取消" : reason);
        return true;
    }

    /** 记录当前执行线程：取消时用来中断阻塞中的取数/模型调用。仅在 Agent Worker 线程内调用。 */
    public synchronized void markRunning() {
        this.runner = Thread.currentThread();
        budget.enter();
        if (deadlines != null && deadlineTimer == null && status.active()) {
            deadlineTimer = deadlines.schedule(this::expire,
                    budget.remaining().toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    /** 执行线程退场前清除记录，避免持有线程引用。 */
    public synchronized void clearRunning() {
        this.runner = null;
        if (deadlineTimer != null) {
            deadlineTimer.cancel(false);
        }
        budget.leave();
    }

    /** 截止时间只设一次；持续输出不延期，迟到的完成/失败不得覆盖这个终态。 */
    private void expire() {
        synchronized (this) {
            if (!status.active()) {
                return;
            }
            cancelled = true;
            fail(budget.timeoutReason());
        }
        budget.stop(budget.timeoutReason());
        interruptRunner();
    }

    /** 中断执行线程：最差情况是阻塞调用自然返回后由检查点兜底，不会损坏状态。 */
    public synchronized void interruptRunner() {
        if (runner != null) {
            runner.interrupt();
        }
    }

    /**
     * 记录一步调查进度：同名且仍在执行中的步骤被就地更新，避免同一阶段出现重复行。
     *
     * {@code detail} 的含义随状态而定：执行中作为输入说明，终态作为结果说明；失败时作为失败原因。
     * 就地更新时沿用首次上报的时刻作为开始时间，不伪造时间戳。
     */
    @Override
    public synchronized void step(AgentStepType type, String name, StepStatus status, String detail) {
        if (this.status.terminal()) {
            return;
        }
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

    /** 指认一场旧对话：结论是那张卡片，候选卡片一并落在快照里给前端点。 */
    public synchronized void answerRecall(String summary, List<RecallChoice> choices) {
        this.recalls = choices == null ? List.of() : List.copyOf(choices);
        complete(new InvestigationReport(summary == null ? "" : summary, Confidence.MEDIUM,
                List.of(), List.of(), List.of(), null));
    }

    public synchronized void complete(InvestigationReport report) {
        if (status.terminal() || cancelled()) {
            return;
        }
        this.report = report;
        this.completedAtMillis = System.currentTimeMillis();
        this.status = TaskStatus.COMPLETED;
        mergeEvidence(report.evidence());
        persist();
        // 证据与结论同时落定：事件给观察者一个明确的「证据已产出」通知，内容与快照完全一致。
        publish(TaskEventType.EVIDENCE_ADDED, Map.of("evidence", report.evidence(),
                "count", report.evidence().size()));
        publish(TaskEventType.TASK_COMPLETED, Map.of("status", status, "summary", report.summary(),
                "confidence", report.confidence()));
        checkpoint(CheckpointStage.COMPLETED, lastRoundNo, lastToolCallCount, null);
        settle();
    }

    public synchronized void fail(String error) {
        if (status.terminal()) {
            return;
        }
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
                taskType, plan, List.copyOf(executedCapabilities), recalls, List.copyOf(evidence));
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
