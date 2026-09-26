package com.rover.agent.core.planning;

import com.rover.agent.core.capability.AgentCapability;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 调查计划：目标、候选故障原因与按序执行的只读步骤。
 *
 * 计划由模型建议（或由确定性兜底生成），但每一步都要经过 {@link PlanValidator} 校验：
 * 只允许注册表里已接入的只读能力，且不能指定请求目标之外的资源——「模型负责建议，代码负责边界」。
 *
 * @param goal       本次调查要回答的问题
 * @param hypotheses 候选故障原因（供解释与人工核对，不是结论）
 * @param steps      按序执行的只读步骤
 */
public record InvestigationPlan(String goal, List<String> hypotheses, List<PlannedStep> steps) {

    public InvestigationPlan {
        goal = goal == null ? "" : goal.trim();
        hypotheses = hypotheses == null ? List.of() : List.copyOf(hypotheses);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /** 空计划：没有任何可执行步骤（如目标与路径都不足时）。 */
    public static InvestigationPlan empty() {
        return new InvestigationPlan("", List.of(), List.of());
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    /** 计划中出现的能力（按首次出现顺序，去重）。 */
    public Set<AgentCapability> capabilities() {
        Set<AgentCapability> capabilities = new LinkedHashSet<>();
        steps.forEach(step -> capabilities.add(step.capability()));
        return capabilities;
    }

    /** 一句话计划说明：供会话回复与步骤详情使用。 */
    public String describe() {
        if (steps.isEmpty()) {
            return "无可用调查步骤";
        }
        StringBuilder text = new StringBuilder(goal.isBlank() ? "调查计划" : goal).append("：");
        for (int i = 0; i < steps.size(); i++) {
            PlannedStep step = steps.get(i);
            text.append(i == 0 ? "" : " → ").append(step.capability());
        }
        return text.toString();
    }
}