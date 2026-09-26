package com.rover.agent.core.planning;

/**
 * 调查规划器：给出「下一步查什么」与「是否可以收尾」。
 *
 * 实现有两类：
 * <ul>
 *   <li>确定性兜底（{@link RuleBasedPlanner}）：无模型时也必须能产出计划，保证既有故障调查链路不回归；</li>
 *   <li>模型辅助（runtime 的 LLM 规划器）：在兜底计划之上吸收模型的补充建议，但每一步都要过
 *       {@link PlanValidator}——模型提不出注册表以外的能力。</li>
 * </ul>
 *
 * 计划本身不执行任何动作，也不产生写操作：{@link PlannedStep} 只携带能力标识与目标。
 */
public interface InvestigationPlanner {

    /** 产出本轮调查计划。 */
    InvestigationPlan plan(PlanningRequest request);

    /** 依据当前进度评估：继续下一轮、结束，还是澄清。 */
    PlanningDecision evaluate(PlanningRequest request, PlanProgress progress);
}