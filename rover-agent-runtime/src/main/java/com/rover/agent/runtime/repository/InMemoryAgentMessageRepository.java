package com.rover.agent.runtime.repository;

import com.rover.agent.core.model.AgentMessage;
import com.rover.agent.core.repository.AgentMessageRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 会话消息的内存实现。
 *
 * 容量按「全部会话累计条数」限制：这是刻意的短期上下文窗口，保留策略在容量满时丢弃最早的对话，
 * 与 {@code AgentContextManager} 只取最近若干条交给模型是同一个口径。
 * 消息是叶子记录（除会话外无人引用它），因此丢弃不产生孤儿引用；会话级联清理时按会话整组删除。
 */
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