package com.rover.agent.core.planning;

/**
 * 生成只读调查计划并判断是否继续，支持规则兜底与模型辅助。
 * 计划须通过 {@link PlanValidator} 校验。
 */
public interface InvestigationPlanner {

    /** 产出本轮调查计划。 */
    InvestigationPlan plan(PlanningRequest request);

    /** 依据当前进度评估：继续下一轮、结束，还是澄清。 */
    PlanningDecision evaluate(PlanningRequest request, PlanProgress progress);
}