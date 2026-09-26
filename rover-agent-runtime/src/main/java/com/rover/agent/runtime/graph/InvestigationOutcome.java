package com.rover.agent.runtime.graph;

import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.investigation.Findings;
import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.planning.InvestigationPlan;
import java.util.List;

/**
 * 一次只读调查的产出：结论合成结果，外加支撑它的证据、判断边界与执行过程。
 *
 * {@code plan / executedCapabilities / rounds / toolCalls} 是 P2 动态调查的观察面：
 * 结论之外还要能说清「Agent 查了哪几步、走了几轮、有没有触到限制」。
 * {@code clarification} 非空表示规划认为继续调查需要用户补充信息，此时没有结论。
 *
 * @param findings             假设验证结论与置信度；需要澄清时为 {@code null}
 * @param evidence             已采集的只读证据
 * @param limitations          本次判断的适用边界
 * @param plan                 最终执行的调查计划
 * @param executedCapabilities 已执行（或按事实跳过）的能力
 * @param rounds               实际经过的规划轮数
 * @param toolCalls            实际发生的能力调用次数
 * @param clarification        需要用户补充信息时的提问；否则为 {@code null}
 */
public record InvestigationOutcome(Findings findings, List<Evidence> evidence, List<String> limitations,
                                   InvestigationPlan plan, List<AgentCapability> executedCapabilities,
                                   int rounds, int toolCalls, String clarification) {

    public InvestigationOutcome {
        plan = plan == null ? InvestigationPlan.empty() : plan;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        executedCapabilities = executedCapabilities == null ? List.of() : List.copyOf(executedCapabilities);
        clarification = clarification == null || clarification.isBlank() ? null : clarification.trim();
    }

    /** 兼容构造器：只关心结论、证据与边界的调用方。 */
    public InvestigationOutcome(Findings findings, List<Evidence> evidence, List<String> limitations) {
        this(findings, evidence, limitations, InvestigationPlan.empty(), List.of(), 0, 0, null);
    }

    /** 是否停在澄清点：规划认为继续调查缺少必要信息。 */
    public boolean needsClarification() {
        return clarification != null;
    }
}