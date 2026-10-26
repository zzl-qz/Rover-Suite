package com.rover.agent.core.planning;

import com.rover.agent.core.capability.AgentCapability;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 调查计划快照：目标、候选故障原因与按序执行的只读步骤。
 *
 * 保留该类型供历史任务快照和数据库记录兼容；当前告警与对话调查都通过共享 ReAct 工具循环执行，
 * 不再通过计划器生成步骤。
 *
 * @param goal       本次调查要回答的问题
 * @param hypotheses 候选故障原因（供解释与人工核对，不是结论）
 * @param steps      按序执行的只读步骤
 */
public record InvestigationPlan(String goal, List<String> hypotheses, List<PlannedStep> steps,
                                List<com.rover.agent.core.model.Hypothesis> assessments, PlanProgress progress) {

    public InvestigationPlan {
        goal = goal == null ? "" : goal.trim();
        hypotheses = hypotheses == null ? List.of() : List.copyOf(hypotheses);
        steps = steps == null ? List.of() : List.copyOf(steps);
        assessments = assessments == null ? List.of() : List.copyOf(assessments);
        progress = progress == null ? PlanProgress.empty() : progress;
    }

    public InvestigationPlan(String goal, List<String> hypotheses, List<PlannedStep> steps) {
        this(goal, hypotheses, steps, List.of(), PlanProgress.empty());
    }

    public InvestigationPlan assessed(List<com.rover.agent.core.model.Hypothesis> items, PlanProgress state) {
        return new InvestigationPlan(goal, hypotheses, steps, items, state);
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
