package com.rover.agent.runtime.repository;

import com.rover.agent.core.model.AgentMessage;
import com.rover.agent.core.repository.AgentMessageRepository;
import java.util.List;

/**
 * 会话消息的内存实现。
 *
 * 容量按「全部会话累计条数」限制：超出后淘汰最早的消息，正好承担「只保留短期上下文」的角色。
 */
public final class InMemoryAgentMessageRepository implements AgentMessageRepository {

    private final BoundedStore<String, AgentMessage> messages;

    public InMemoryAgentMessageRepository(int capacity) {
        this.messages = new BoundedStore<>(capacity);
    }

    @Override
    public void save(AgentMessage message) {
        messages.put(message.messageId(), message);
    }

    @Override
    public List<AgentMessage> bySession(String sessionId) {
        return messages.valuesMatching(message -> message.sessionId().equals(sessionId));
    }

    @Override
    public void removeBySession(String sessionId) {
        messages.removeMatching(message -> message.sessionId().equals(sessionId));
    }
}