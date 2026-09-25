package com.rover.agent.runtime.task;

import com.rover.agent.core.model.InvestigationReport;
import com.rover.agent.core.model.Step;
import com.rover.agent.core.model.StepStatus;
import com.rover.agent.core.model.TaskStatus;
import com.rover.agent.core.model.TaskView;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次调查任务的运行状态；状态变更与视图快照都在同步块内完成，保证轮询读到的是一致视图。
 */
public final class InvestigationTask {

    private final String taskId;
    private final String sessionId;
    private final String incidentId;
    private final String path;
    private final String question;
    private final long createdAtMillis = System.currentTimeMillis();
    private final List<Step> steps = new ArrayList<>();
    private TaskStatus status = TaskStatus.PENDING;
    private long completedAtMillis;
    private InvestigationReport report;
    private String error;

    InvestigationTask(String taskId, String sessionId, String incidentId, String path, String question) {
        this.taskId = taskId;
        this.sessionId = sessionId;
        this.incidentId = incidentId;
        this.path = path;
        this.question = question;
    }

    public String taskId() {
        return taskId;
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
    }

    /**
     * 记录一步调查进度：同名且仍在执行中的步骤被就地更新，避免同一阶段出现重复行。
     */
    public synchronized void step(String name, StepStatus status, String detail) {
        if (status != StepStatus.RUNNING) {
            for (int i = steps.size() - 1; i >= 0; i--) {
                Step previous = steps.get(i);
                if (name.equals(previous.name()) && previous.status() == StepStatus.RUNNING) {
                    steps.set(i, new Step(name, status, detail));
                    return;
                }
            }
        }
        steps.add(new Step(name, status, detail));
    }

    public synchronized void complete(InvestigationReport report) {
        this.report = report;
        this.completedAtMillis = System.currentTimeMillis();
        this.status = TaskStatus.COMPLETED;
    }

    public synchronized void fail(String error) {
        this.error = error;
        this.completedAtMillis = System.currentTimeMillis();
        this.status = TaskStatus.FAILED;
    }

    public synchronized boolean terminal() {
        return status == TaskStatus.COMPLETED || status == TaskStatus.FAILED;
    }

    public synchronized TaskView view() {
        return new TaskView(taskId, sessionId, incidentId, status, path, question, createdAtMillis,
                completedAtMillis, List.copyOf(steps), report, error);
    }
}