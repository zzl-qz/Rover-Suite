package com.rover.agent.core.model;

import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.planning.InvestigationPlan;
import java.util.List;

/** 任务对外视图：Admin 轮询与任务记录都使用这个对象。 */
public record TaskView(String taskId, String sessionId, String incidentId, TaskStatus status,
                       AgentStepType currentStage, String path, ResourceTarget target, String question,
                       long createdAtMillis, long completedAtMillis, List<Step> steps,
                       InvestigationReport result, String error, String clarification,
                       TaskType taskType, InvestigationPlan plan,
                       List<AgentCapability> executedCapabilities,
                       List<RecallChoice> recalls) {

    public TaskView {
        taskType = taskType == null ? TaskType.INVESTIGATION : taskType;
        plan = plan == null ? InvestigationPlan.empty() : plan;
        executedCapabilities = executedCapabilities == null ? List.of()
                : List.copyOf(executedCapabilities);
        recalls = recalls == null ? List.of() : List.copyOf(recalls);
    }

    /** 兼容构造器：只关心调查链的调用方不必显式给出任务类型，默认按自动调查形态展示。 */
    public TaskView(String taskId, String sessionId, String incidentId, TaskStatus status,
                    AgentStepType currentStage, String path, ResourceTarget target, String question,
                    long createdAtMillis, long completedAtMillis, List<Step> steps,
                    InvestigationReport result, String error, String clarification) {
        this(taskId, sessionId, incidentId, status, currentStage, path, target, question, createdAtMillis,
                completedAtMillis, steps, result, error, clarification, TaskType.INVESTIGATION, null,
                List.of(), null);
    }

    /** 是否给出了调查计划：前端据此决定是否展示计划区。 */
    public boolean planned() {
        return !plan.isEmpty();
    }

    /** 进程重启时，还在执行的任务不能假装还在跑。 */
    public TaskView interrupted(String reason) {
        return new TaskView(taskId, sessionId, incidentId, TaskStatus.FAILED, currentStage, path, target, question,
                createdAtMillis, System.currentTimeMillis(), steps, result, reason, clarification, taskType,
                plan, executedCapabilities, recalls);
    }
}
