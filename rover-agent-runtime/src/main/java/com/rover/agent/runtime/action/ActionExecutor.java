package com.rover.agent.runtime.action;

import com.rover.agent.core.model.ActionType;
import com.rover.agent.core.model.AgentAction;

/**
 * 确定性执行已获审批的变更，执行前由调用方 CAS 迁移状态。
 * 提交前保存幂等号，执行后回读验证；执行、回滚和确认均返回已保存的结果。
 * 业务失败记录为状态与说明，不向外抛出。
 */
public interface ActionExecutor {

    /** 能执行哪种变更。 */
    ActionType type();

    /** 执行变更：预检 → 预览 → 幂等提交 → 回读验证。 */
    AgentAction execute(AgentAction action);

    /** 补偿回滚：用同一个窄原语把权重改回变更前的值，并再次回读确认。 */
    AgentAction rollback(AgentAction action);

    /** 结果确认：用原 operationId 回查一次「到底生效了没有」，并接着做验证。 */
    AgentAction resolve(AgentAction action);
}
