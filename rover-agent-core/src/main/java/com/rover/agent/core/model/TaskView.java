package com.rover.agent.core.model;

import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.planning.InvestigationPlan;
import java.util.List;

/**
 * 任务对外视图：Admin 轮询与任务记录都使用这个对象。
 *
 * <p>{@code evidence} 是「本次执行已取到的全部证据」，与结论是否已经产出无关——工具一返回，
 * 取到的事实就成立。结论里的证据（{@link InvestigationReport#evidence()}）是同一批：
 * 报告存的是「这次结论建立在哪些事实上」，两者在完成态下逐条一致。
 */
public record TaskView(String taskId, String sessionId, String incidentId, TaskStatus status,
                       AgentStepType currentStage, String path, ResourceTarget target, String question,
                       long createdAtMillis, long completedAtMillis, List<Step> steps,
                       InvestigationReport result, String error, String clarification,
                       TaskType taskType, InvestigationPlan plan,
                       List<AgentCapability> executedCapabilities,
                       List<RecallChoice> recalls, List<Evidence> evidence) {

    public TaskView {
        taskType = taskType == null ? TaskType.INVESTIGATION : taskType;
        plan = plan == null ? InvestigationPlan.empty() : plan;
        executedCapabilities = executedCapabilities == null ? List.of()
                : List.copyOf(executedCapabilities);
        recalls = recalls == null ? List.of() : List.copyOf(recalls);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    /** 兼容构造器：只关心调查链的调用方不必显式给出任务类型与计划。 */
    public TaskView(String taskId, String sessionId, String incidentId, TaskStatus status,
                    AgentStepType currentStage, String path, ResourceTarget target, String question,
                    long createdAtMillis, long completedAtMillis, List<Step> steps,
                    InvestigationReport result, String error, String clarification) {
        this(taskId, sessionId, incidentId, status, currentStage, path, target, question, createdAtMillis,
                completedAtMillis, steps, result, error, clarification, TaskType.INVESTIGATION, null,
                List.of(), null, null);
    }

    /** 兼容构造器：只关心任务形态与计划、不显式给出证据的调用方走这里。 */
    public TaskView(String taskId, String sessionId, String incidentId, TaskStatus status,
                    AgentStepType currentStage, String path, ResourceTarget target, String question,
                    long createdAtMillis, long completedAtMillis, List<Step> steps,
                    InvestigationReport result, String error, String clarification, TaskType taskType,
                    InvestigationPlan plan, List<AgentCapability> executedCapabilities,
                    List<RecallChoice> recalls) {
        this(taskId, sessionId, incidentId, status, currentStage, path, target, question, createdAtMillis,
                completedAtMillis, steps, result, error, clarification, taskType, plan, executedCapabilities,
                recalls, null);
    }

    /** 是否给出了调查计划：前端据此决定是否展示计划区。 */
    public boolean planned() {
        return !plan.isEmpty();
    }
}
