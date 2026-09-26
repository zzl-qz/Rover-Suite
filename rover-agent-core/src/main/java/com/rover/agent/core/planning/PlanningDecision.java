package com.rover.agent.core.planning;

/**
 * 规划评估结果：是否继续调查，以及继续/澄清时的说明。
 *
 * @param verdict       评估结论
 * @param reason        评估依据（写入步骤说明，解释「为什么还要查」或「为什么可以收尾」）
 * @param clarification 澄清提问；仅 {@link PlanningVerdict#CLARIFY} 时有值
 */
public record PlanningDecision(PlanningVerdict verdict, String reason, String clarification) {

    public PlanningDecision {
        verdict = verdict == null ? PlanningVerdict.FINISH : verdict;
        reason = reason == null ? "" : reason.trim();
        clarification = clarification == null || clarification.isBlank() ? null : clarification.trim();
    }

    /** 继续下一轮调查。 */
    public static PlanningDecision continuePlanning(String reason) {
        return new PlanningDecision(PlanningVerdict.CONTINUE, reason, null);
    }

    /** 结束调查，进入结论综合。 */
    public static PlanningDecision finish(String reason) {
        return new PlanningDecision(PlanningVerdict.FINISH, reason, null);
    }

    /** 需要用户补充信息。 */
    public static PlanningDecision clarify(String clarification) {
        return new PlanningDecision(PlanningVerdict.CLARIFY, "缺少继续调查所需的信息", clarification);
    }

    public boolean shouldContinue() {
        return verdict == PlanningVerdict.CONTINUE;
    }

    public boolean shouldClarify() {
        return verdict == PlanningVerdict.CLARIFY;
    }
}