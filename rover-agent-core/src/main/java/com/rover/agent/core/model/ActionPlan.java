package com.rover.agent.core.model;

/**
 * 受控处置计划：把用户想做的操作整理成一份可人工审核的建议，本阶段一律不执行。
 *
 * {@code executable} 恒为 {@code false}：审批、审计与任务持久化尚未落地，任何「已执行」的表述都是谎报。
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