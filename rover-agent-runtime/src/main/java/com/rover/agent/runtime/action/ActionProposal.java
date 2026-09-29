package com.rover.agent.runtime.action;

import com.rover.agent.core.model.AgentAction;

/**
 * 一次变更提议的结果。
 *
 * <p>「提不了」是正常结果而不是错误：路由没匹配上、版本不存在、权重没变、Gateway 读不到，
 * 都要变成一句有依据的说明回到对话里，让模型如实转述，而不是抛异常或凭空编一张卡出来。
 *
 * <p>其中有一类「提不了」必须和失败区分开：<b>语义不明确</b>。用户说「放量到 20」时，
 * 20 是权重值还是 20% 流量，两种读法算出来的写入值能差两个数量级。
 * 这种情况不该替用户选一个，而要让模型回去问清楚——{@link #clarificationRequired} 就是这个信号，
 * 它要求模型把问题抛回给用户，而不是继续猜一个数值再提一次。
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
