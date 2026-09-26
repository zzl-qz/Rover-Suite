package com.rover.agent.core.planning;

import com.rover.agent.core.capability.AgentCapability;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 规划进度：已经走过多少轮、调过多少次能力、还有哪些能力已经处理过。
 *
 * {@code settled} 包含「执行过」与「按事实判定跳过」两种：跳过的能力不能再被重新规划，
 * 否则「无健康实例后跳过指标」这类正确判断会被下一轮规划反复推翻。
 *
 * @param rounds       已完成的规划轮数
 * @param toolCalls    已执行的能力调用次数
 * @param settled      已处理过的能力（执行过或确定跳过）
 * @param remainingSteps 本轮计划中尚未处理的步骤数
 */
public record PlanProgress(int rounds, int toolCalls, Set<AgentCapability> settled, int remainingSteps) {

    public PlanProgress {
        settled = settled == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(settled));
    }

    public static PlanProgress empty() {
        return new PlanProgress(0, 0, Set.of(), 0);
    }

    /** 用当前进度开一次评估。 */
    public static PlanProgress of(int rounds, int toolCalls, Set<AgentCapability> settled, int remainingSteps) {
        return new PlanProgress(rounds, toolCalls, settled, remainingSteps);
    }

    /** 是否还有本轮未执行的步骤。 */
    public boolean hasRemainingSteps() {
        return remainingSteps > 0;
    }

    public List<AgentCapability> settledList() {
        return List.copyOf(settled);
    }
}