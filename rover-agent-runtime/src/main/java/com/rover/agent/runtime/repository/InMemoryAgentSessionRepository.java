package com.rover.agent.runtime.repository;

import com.rover.agent.core.model.Session;
import com.rover.agent.core.repository.AgentSessionRepository;
import java.util.List;
import java.util.Optional;

/** 会话的内存实现：单机、进程内、重启即失。 */
public final class InMemoryAgentSessionRepository implements AgentSessionRepository {

    private final BoundedStore<String, Session> sessions;

    public InMemoryAgentSessionRepository(int capacity) {
        this.sessions = new BoundedStore<>(capacity);
    }

    @Override
    public void save(Session session) {
        sessions.put(session.sessionId(), session);
    }

    @Override
    public Optional<Session> find(String sessionId) {
        return sessions.get(sessionId);
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