package com.rover.agent.core.planning;

import com.rover.agent.core.capability.AgentCapability;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 规划进度：已经走过多少轮、调过多少次能力、还有哪些能力已经处理过。
 *
 * {@code settled} 包含已完成、永久不可用、重试用尽与按事实跳过，不包含尚有补查机会的瞬态失败。
 * 跳过的能力不能再被重新规划，
 * 否则「无健康实例后跳过指标」这类正确判断会被下一轮规划反复推翻。
 *
 * @param rounds       已完成的规划轮数
 * @param toolCalls    已执行的能力调用次数
 * @param settled      已处理过的能力（执行过或确定跳过）
 * @param remainingSteps 本轮计划中尚未处理的步骤数
 */
public record PlanProgress(int rounds, int toolCalls, Set<AgentCapability> settled, int remainingSteps,
                           List<CapabilityAttempt> attempts,
                           com.rover.agent.core.investigation.FindingsInput facts, String stopReason,
                           List<String> limitations) {

    public PlanProgress {
        settled = settled == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(settled));
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        stopReason = stopReason == null ? "" : stopReason;
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    public PlanProgress(int rounds, int toolCalls, Set<AgentCapability> settled, int remainingSteps) {
        this(rounds, toolCalls, settled, remainingSteps, List.of(), null, "", List.of());
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
