package com.rover.agent.core.model;

/**
 * 受控处置计划：把「用户想做的操作」整理成一份可人工审核的建议，本阶段一律不执行。
 *
 * {@code executable} 恒为 {@code false}：这不是调用方的自觉，而是领域模型的硬约束——
 * 审批、审计与任务持久化尚未落地，任何「已执行」的表述都会是谎报。计划中的每个字段都可追溯到
 * 一次只读预检（证据留在任务报告里），因此人工照单执行前能核对当前状态。
 *
 * @param actionType        处置动作类型
 * @param targetDescription 用户表述的目标原文（如 {@code order-03}）；用于在未解析出对象时仍可追溯
 * @param target            预检尝试解析出的对象；未解析出时为 {@link ResourceTarget#unknown()}
 * @param reason            建议该动作的原因（来自用户请求与预检事实）
 * @param riskLevel         风险等级
 * @param currentState      预检看到的当前状态
 * @param desiredState      执行后应达到的状态
 * @param expectedImpact    预期影响（容量、流量口径）
 * @param verificationPlan  执行后的验证方式
 * @param rollbackPlan      回滚方式
 * @param executable        是否可执行；本阶段恒为 false
 * @param blockedReason     不可执行的原因说明
 */
public record ActionPlan(ActionType actionType, String targetDescription, ResourceTarget target, String reason,
                         RiskLevel riskLevel, String currentState, String desiredState, String expectedImpact,
                         String verificationPlan, String rollbackPlan, boolean executable, String blockedReason) {

    /** 不可执行的统一说明：让前端与文档只需要引用一处措辞。 */
    public static final String NOT_EXECUTABLE_REASON = "当前版本尚未实现审批、审计与任务持久化，仅生成处置计划，不执行任何写操作。";

    public ActionPlan {
        actionType = actionType == null ? ActionType.UNKNOWN : actionType;
        targetDescription = targetDescription == null ? "" : targetDescription.trim();
        target = target == null ? ResourceTarget.unknown() : target;
        reason = reason == null ? "" : reason.trim();
        riskLevel = riskLevel == null ? RiskLevel.HIGH : riskLevel;
        currentState = currentState == null ? "" : currentState.trim();
        desiredState = desiredState == null ? "" : desiredState.trim();
        expectedImpact = expectedImpact == null ? "" : expectedImpact.trim();
        verificationPlan = verificationPlan == null ? "" : verificationPlan.trim();
        rollbackPlan = rollbackPlan == null ? "" : rollbackPlan.trim();
        // 领域模型层的硬约束：本阶段不存在「可执行的处置计划」这种对象。
        executable = false;
        blockedReason = blockedReason == null || blockedReason.isBlank() ? NOT_EXECUTABLE_REASON : blockedReason.trim();
    }

    /** 一句话摘要，供会话回复与任务结论使用。 */
    public String summary() {
        return "已生成受控处置计划：" + actionType.label() + "（目标 " + targetLabel() + "，风险 "
                + riskLevel + "）；" + blockedReason;
    }

    /** 目标展示名：优先用解析出的对象取值，未解析出时退回用户原文。 */
    public String targetLabel() {
        return target.value().isBlank() ? (targetDescription.isBlank() ? "未指定" : targetDescription) : target.value();
    }
}