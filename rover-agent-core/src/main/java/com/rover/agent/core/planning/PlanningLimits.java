package com.rover.agent.core.planning;

/**
 * 调查规划轮数、能力调用次数和单轮步骤数上限，由代码强制执行。
 *
 * @param maxRounds    最多规划轮数
 * @param maxToolCalls 最多能力调用次数
 * @param maxPlanSteps 单轮计划最多步骤数
 */
public record PlanningLimits(int maxRounds, int maxToolCalls, int maxPlanSteps) {

    public PlanningLimits {
        if (maxRounds <= 0 || maxToolCalls <= 0 || maxPlanSteps <= 0) {
            throw new IllegalArgumentException("规划限制必须为正数");
        }
    }

    /** 本阶段默认限制：3 轮 / 10 次能力调用 / 单轮 6 步。 */
    public static PlanningLimits defaults() {
        return new PlanningLimits(3, 10, 6);
    }
}