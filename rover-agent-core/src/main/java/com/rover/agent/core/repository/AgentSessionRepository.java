package com.rover.agent.core.repository;

import com.rover.agent.core.model.Session;
import java.util.List;
import java.util.Optional;

/**
 * 会话存储契约，按认证用户隔离查询结果。
 * 内存实现容量满时拒绝新增，由上层保留策略整组清理。
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