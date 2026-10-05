package com.rover.agent.runtime.repository;

import com.rover.agent.core.model.AgentMessage;
import com.rover.agent.core.repository.AgentMessageRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 消息内存存储，容量限制为全部会话的累计条数，由上层保留策略清理。 */
public final class InMemoryAgentMessageRepository implements AgentMessageRepository {

    private final BoundedStore<String, AgentMessage> messages;
    private final int capacity;

    public InMemoryAgentMessageRepository(int capacity) {
        this.messages = new BoundedStore<>(capacity);
        this.capacity = capacity;
    }

    @Override
    public void save(AgentMessage message) {
        if (!messages.put(message.messageId(), message)) {
            throw new StoreCapacityExceededException("消息存储已满（容量 " + capacity + "），无法追加新消息");
        }
    }

    @Override
    public List<AgentMessage> bySession(String sessionId) {
        return messages.valuesMatching(message -> Objects.equals(message.sessionId(), sessionId));
    }

    @Override
    public List<AgentMessage> listAll() {
        return messages.values();
    }

    @Override
    public Optional<AgentMessage> oldest() {
        return messages.oldest();
    }

    @Override
    public void remove(String messageId) {
        messages.remove(messageId);
    }

    @Override
    public int removeBySession(String sessionId) {
        return messages.removeMatching(message -> Objects.equals(message.sessionId(), sessionId));
    }
}