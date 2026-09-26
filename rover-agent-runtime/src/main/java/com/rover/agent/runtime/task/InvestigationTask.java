package com.rover.agent.runtime.task;

import com.rover.agent.core.model.AgentStepType;
import com.rover.agent.core.model.InvestigationReport;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.Step;
import com.rover.agent.core.model.StepStatus;
import com.rover.agent.core.model.TaskStatus;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.runtime.graph.StepSink;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 一次调查任务的运行状态；状态变更与视图快照都在同步块内完成，保证轮询读到的是一致视图。
 *
 * 每次状态变更都把最新快照推给 {@code snapshotSink}（由任务登记方接到任务记录存储上），
 * 因此任务记录的真相来源是存储而不是本对象；本对象只额外持有进程内专属的执行态
 * （解读增量缓冲与订阅者）。
 */
public final class InvestigationTask implements StepSink {

    private final String taskId;
    private final String sessionId;
    private final String incidentId;
    private final String path;
    private final ResourceTarget target;
    private final String question;
    private final Consumer<TaskView> snapshotSink;
    private final long createdAtMillis = System.currentTimeMillis();
    private final List<Step> steps = new ArrayList<>();
    private final StringBuilder analysis = new StringBuilder();
    private final List<AnalysisStreamListener> analysisListeners = new ArrayList<>();
    private TaskStatus status = TaskStatus.PENDING;
    private AgentStepType currentStage;
    private long completedAtMillis;
    private InvestigationReport report;
    private String error;
    private boolean analysisClosed;

    InvestigationTask(String taskId, String sessionId, String incidentId, String path, ResourceTarget target,
                      String question, Consumer<TaskView> snapshotSink) {
        this.taskId = taskId;
        this.sessionId = sessionId;
        this.incidentId = incidentId;
        this.path = path;
        this.target = target == null ? ResourceTarget.route(path) : target;
        this.question = question;
        this.snapshotSink = snapshotSink;
    }

    public String taskId() {
        return taskId;
    }

    /** 任务所属事件；结论回写事件时按它定位聚合点。 */
    public String incidentId() {
        return incidentId;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public String path() {
        return path;
    }

    public String question() {
        return question;
    }

    /** 调查开始执行。 */
    public synchronized void start() {
        this.status = TaskStatus.RUNNING;
        persist();
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
                    steps.set(i, finish(previous, type, status, detail));
                    persist();
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
    }

    /** 把执行中的步骤就地改成终态：沿用原开始时刻，只补结束信息。 */
    private static Step finish(Step previous, AgentStepType type, StepStatus status, String detail) {
        return new Step(previous.stepId(), previous.taskId(), type, previous.name(), status, previous.inputSummary(),
                status == StepStatus.FAILED ? null : detail, previous.startedAtMillis(), System.currentTimeMillis(),
                status == StepStatus.FAILED ? detail : null);
    }

    public synchronized void complete(InvestigationReport report) {
        this.report = report;
        this.completedAtMillis = System.currentTimeMillis();
        this.status = TaskStatus.COMPLETED;
        persist();
    }

    public synchronized void fail(String error) {
        this.error = error;
        this.completedAtMillis = System.currentTimeMillis();
        this.status = TaskStatus.FAILED;
        persist();
    }

    /** 推送模型解读增量：先落缓冲再转发，晚连上的订阅者才能靠补发对齐前缀。 */
    public synchronized void appendAnalysis(String chunk) {
        if (analysisClosed || chunk == null || chunk.isEmpty()) {
            return;
        }
        analysis.append(chunk);
        for (AnalysisStreamListener listener : analysisListeners) {
            listener.onDelta(chunk);
        }
    }

    /**
     * 标注解读结束：订阅者收到结束通知，之后不再接受增量。
     * 未配置模型、模型不可用、解读失败与成功都要走到这里，否则订阅者会一直等到连接超时。
     */
    public synchronized void closeAnalysis() {
        if (analysisClosed) {
            return;
        }
        analysisClosed = true;
        List<AnalysisStreamListener> listeners = List.copyOf(analysisListeners);
        analysisListeners.clear();
        for (AnalysisStreamListener listener : listeners) {
            listener.onComplete();
        }
    }

    /**
     * 订阅解读增量：先补发已产生的全文，再按增量转发；订阅时解读已结束的补发后立即通知结束。
     *
     * 补发与登记在同一同步块内完成，保证增量既不会漏发也不会重复；代价是回调在任务锁内执行。
     */
    public synchronized void subscribeAnalysis(AnalysisStreamListener listener) {
        listener.onSnapshot(analysis.toString());
        if (analysisClosed) {
            listener.onComplete();
            return;
        }
        analysisListeners.add(listener);
    }

    /** 退订：客户端断开、连接超时或出错时调用，避免继续向已关闭的连接写入。 */
    public synchronized void unsubscribeAnalysis(AnalysisStreamListener listener) {
        analysisListeners.remove(listener);
    }

    public synchronized boolean terminal() {
        return status == TaskStatus.COMPLETED || status == TaskStatus.FAILED;
    }

    public synchronized TaskView view() {
        return new TaskView(taskId, sessionId, incidentId, status, currentStage, path, target, question,
                createdAtMillis, completedAtMillis, List.copyOf(steps), report, error);
    }

    /** 把最新快照推给任务记录存储；调研期间读到的始终是一致视图。 */
    private void persist() {
        snapshotSink.accept(view());
    }
}