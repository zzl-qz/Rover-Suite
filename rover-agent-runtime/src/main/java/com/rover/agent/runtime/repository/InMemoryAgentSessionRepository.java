package com.rover.agent.runtime.repository;

import com.rover.agent.core.model.Session;
import com.rover.agent.core.repository.AgentSessionRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 会话的内存实现：单机、进程内、重启即失。
 *
 * 容量满时拒绝写入（{@link StoreCapacityExceededException}），不淘汰已有会话：
 * 会话被丢掉而它的事件、消息与任务还在，读侧就会出现指向不存在会话的孤儿记录。
 * 腾出位置由上层保留策略做整组清理，见 {@code WorkspaceRetention}。
 */
public final class InMemoryAgentSessionRepository implements AgentSessionRepository {

    private final BoundedStore<String, Session> sessions;
    private final int capacity;

    public InMemoryAgentSessionRepository(int capacity) {
        this.sessions = new BoundedStore<>(capacity);
        this.capacity = capacity;
    }

    @Override
    public void save(Session session) {
        if (!sessions.put(session.sessionId(), session)) {
            throw new StoreCapacityExceededException("会话存储已满（容量 " + capacity + "），无法登记新会话");
        }
    }

    @Override
    public Optional<Session> find(String sessionId) {
        return sessions.get(sessionId);
    }

    @Override
    public List<Session> findByUserId(String userId) {
        return sessions.valuesMatching(session -> Objects.equals(session.userId(), userId));
    }

    @Override
    public List<Session> listAll() {
        return sessions.values();
    }

    @Override
    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }
}