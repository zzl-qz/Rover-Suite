package com.rover.agent.core.model;

import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.planning.InvestigationPlan;
import java.util.List;

/**
 * 任务对外视图：Admin 轮询与任务记录都使用这个对象。
 *
 * {@code currentStage} 是当前（或最近一个）阶段的类型，供前端把进度归位到固定阶段；
 * {@code path} 保留为被调查的请求路径（兼容既有页面与旧接口），{@code target} 是结构化的调查对象。
 *
 * P2 起一个任务不再只能是「故障调查」：{@code taskType} 说明本次是查状态、做调查、出处置计划
 * 还是解释说明；{@code intent} 是识别出的意图，{@code plan} 是实际执行的只读调查计划，
 * {@code executedCapabilities} 记录真正调用过的能力，{@code actionPlan} 承载不可执行的处置计划。
 * 这些字段都由同一条快照随 JSON 暴露，前端不依赖事件顺序也能还原全过程。
 *
 * @param taskId               任务 ID
 * @param sessionId            任务所属会话
 * @param incidentId           任务所属事件；目标解析完成前可能为 {@code null}，由执行线程回填
 * @param status               任务状态
 * @param currentStage         当前阶段；尚未开始任何步骤时为 {@code null}
 * @param path                 被调查的请求路径；目标解析完成前为空串
 * @param target               被调查的资源对象；目标解析完成前为未知对象
 * @param question             用户问题
 * @param createdAtMillis      创建时刻
 * @param completedAtMillis    结束时刻；未结束时为 0
 * @param steps                步骤记录
 * @param result               结论报告；未完成时为 null
 * @param error                失败说明；成功时为 null
 * @param clarification        等待用户补充信息时的澄清提问；其他状态为 null
 * @param taskType             任务类型；未识别意图前默认为故障调查
 * @param intent               意图判断；尚未识别时为 null
 * @param plan                 实际执行的只读调查计划；无计划时为空计划
 * @param executedCapabilities 已执行（或按事实跳过）的能力，按首次出现顺序
 * @param actionPlan           处置计划；非处置请求为 null
 */
public record TaskView(String taskId, String sessionId, String incidentId, TaskStatus status,
                       AgentStepType currentStage, String path, ResourceTarget target, String question,
                       long createdAtMillis, long completedAtMillis, List<Step> steps,
                       InvestigationReport result, String error, String clarification,
                       TaskType taskType, IntentDecision intent, InvestigationPlan plan,
                       List<AgentCapability> executedCapabilities, ActionPlan actionPlan) {

    public TaskView {
        taskType = taskType == null ? TaskType.INVESTIGATION : taskType;
        plan = plan == null ? InvestigationPlan.empty() : plan;
        executedCapabilities = executedCapabilities == null ? List.of()
                : List.copyOf(executedCapabilities);
    }

    /** 兼容构造器：P2 之前的调用方只关心调查链，任务类型固定为故障调查。 */
    public TaskView(String taskId, String sessionId, String incidentId, TaskStatus status,
                    AgentStepType currentStage, String path, ResourceTarget target, String question,
                    long createdAtMillis, long completedAtMillis, List<Step> steps,
                    InvestigationReport result, String error, String clarification) {
        this(taskId, sessionId, incidentId, status, currentStage, path, target, question, createdAtMillis,
                completedAtMillis, steps, result, error, clarification, TaskType.INVESTIGATION, null,
                InvestigationPlan.empty(), List.of(), null);
    }

    /** 是否给出了调查计划：前端据此决定是否展示计划区。 */
    public boolean planned() {
        return !plan.isEmpty();
    }
}