package com.rover.agent.runtime.action;

import com.rover.agent.core.model.AgentAction;

/**
 * 变更提议结果；无法提议时返回原因，单位不明确时要求澄清。
 *
 * @param action                产出的待审批变更；没有产出时为空
 * @param reason                没有产出时的原因说明（有产出时为空）
 * @param clarificationRequired 是否为「需要先向用户澄清」：是则不应再尝试自动生成提案
 */
public record ActionProposal(AgentAction action, String reason, boolean clarificationRequired) {

    public static ActionProposal created(AgentAction action) {
        return new ActionProposal(action, "", false);
    }

    public static ActionProposal rejected(String reason) {
        return new ActionProposal(null, reason, false);
    }

    /** 语义不明确：把问题作为澄清请求交回对话，不生成提案。 */
    public static ActionProposal clarificationRequired(String reason) {
        return new ActionProposal(null, reason, true);
    }

    /** 是否真的登记了一条待审批变更。 */
    public boolean created() {
        return action != null;
    }
}
