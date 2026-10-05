package com.rover.agent.runtime.repository;

import com.rover.agent.core.model.Session;
import com.rover.agent.core.repository.AgentSessionRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 会话内存存储；容量满时拒绝新增，由 WorkspaceRetention 整组清理。 */
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