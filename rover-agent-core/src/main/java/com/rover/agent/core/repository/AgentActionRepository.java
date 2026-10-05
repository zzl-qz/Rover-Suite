package com.rover.agent.core.repository;

import com.rover.agent.core.model.ActionStatus;
import com.rover.agent.core.model.AgentAction;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** 变更记录存储契约；transition 必须原子完成状态比较与写入。 */
public interface AgentActionRepository {

    /** 新建或整体覆盖一条变更记录。 */
    void save(AgentAction action);

    /** 按 ID 取一条变更记录。 */
    Optional<AgentAction> find(String actionId);

    /** 某个会话的全部变更记录，新的在前。 */
    List<AgentAction> bySession(String sessionId);

    /**
     * 原子状态迁移：当前状态与 {@code expected} 一致时，把 {@code mutation} 的结果落库并返回；
     * 否则返回空（记录不存在、或状态已被别人改过）。
     *
     * @param actionId 变更 ID
     * @param expected 期望的当前状态
     * @param mutation 迁移函数：输入当前记录，输出迁移后的记录
     * @return 迁移后的记录；CAS 失败或记录不存在时为空
     */
    Optional<AgentAction> transition(String actionId, ActionStatus expected, UnaryOperator<AgentAction> mutation);

    /** 会话被整组清理时一并丢掉它的变更记录；返回删除条数。 */
    int removeBySession(String sessionId);
}
