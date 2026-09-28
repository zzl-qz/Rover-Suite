package com.rover.agent.core.repository;

import com.rover.agent.core.model.Session;
import java.util.List;
import java.util.Optional;

/**
 * 会话存储契约。
 *
 * 业务代码只依赖本接口：配了记录库路径时由关系表实现承载（会话、它的事件、消息与任务都在库里，重启后整条链可恢复），
 * 没配则退回内存实现（单机、进程内、重启即失）。换数据源只换实现，用例层不动。
 * 按会话 ID 查询必须只返回该用户的会话，用户隔离由实现与调用方共同保证。
 *
 * 容量语义：内存实现在容量满时**拒绝写入**（抛 {@code IllegalStateException}），不淘汰已有记录——
 * 会话被丢掉而它的事件、消息与任务还在就会留下孤儿；腾出位置由上层保留策略做整组清理。
 */
public interface AgentSessionRepository {

    /** 保存会话；同 ID 覆盖。 */
    void save(Session session);

    /** 按 ID 查询会话。 */
    Optional<Session> find(String sessionId);

    /**
     * 按归属用户查询会话，按写入顺序（最早在前）。
     *
     * {@code userId} 为 {@code null} 时返回未启用登录时创建的无归属会话；
     * 调用方必须传已规范化的用户身份（空白视为 {@code null}），不能传前端提交的原值。
     */
    List<Session> findByUserId(String userId);

    /** 全部会话，按写入顺序（最早写入在前）。 */
    List<Session> listAll();

    /** 删除会话。 */
    void remove(String sessionId);
}