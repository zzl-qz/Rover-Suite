package com.rover.agent.core.repository;

import com.rover.agent.core.model.Session;
import java.util.List;
import java.util.Optional;

/**
 * 会话存储契约。
 *
 * 业务代码只依赖本接口：当前由内存实现承载（单机、重启即失），后续接入 MySQL 时替换实现即可，
 * 用例层不需要改动。按会话 ID 查询必须只返回该用户的会话，用户隔离由实现与调用方共同保证。
 */
public interface AgentSessionRepository {

    /** 保存会话；同 ID 覆盖。 */
    void save(Session session);

    /** 按 ID 查询会话。 */
    Optional<Session> find(String sessionId);

    /** 全部会话，按写入顺序（最早写入在前）。 */
    List<Session> listAll();

    /** 删除会话。 */
    void remove(String sessionId);
}