package com.rover.agent.runtime.action;

import com.rover.agent.core.model.ActionType;
import com.rover.agent.core.model.AgentAction;

/**
 * 确定性执行器：把一条已经批准的变更落到 Gateway 上，并且<b>只用程序验证过的事实说话</b>。
 *
 * <p>执行器里没有模型、没有提示词、没有「你觉得现在还能不能执行」——每一步都是确定的：
 * 重新读状态做预检、把幂等号先落库再提交、回读确认、必要时用补偿动作回滚。
 * 模型能做的只有「建议改什么」，能做的边界在 {@code propose} 那一侧。
 *
 * <p>三个方法的共同约定：
 *
 * <ul>
 *   <li>入参是已经处于对应状态的记录（{@code execute} 收 EXECUTING，{@code rollback} 收 ROLLING_BACK，
 *       {@code resolve} 收 UNCERTAIN），状态迁移由调用方用 CAS 抢到之后才调用这里；</li>
 *   <li>返回的一定是一条<b>已落库</b>的终态记录，绝不返回「可能改成功了」这种模糊结果；</li>
 *   <li>方法内部不抛业务异常：Gateway 不可达、被拒、超时都会收敛成一个状态 + 一句说明，
 *       因为「执行失败」必须能被记录下来，而不是变成一个没有痕迹的 500。</li>
 * </ul>
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
