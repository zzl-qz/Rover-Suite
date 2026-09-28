package com.rover.agent.runtime.repository;

import com.rover.agent.core.repository.AgentMessageRepository;
import com.rover.agent.core.repository.AgentSessionRepository;

/** 会话和消息的同一份存储。没配路径时是内存，配了就是同一份 H2。 */
public final class ChatLog implements AutoCloseable {

    private final AgentSessionRepository sessions;
    private final AgentMessageRepository messages;
    private final AutoCloseable resource;

    public ChatLog(AgentSessionRepository sessions, AgentMessageRepository messages, AutoCloseable resource) {
        this.sessions = sessions;
        this.messages = messages;
        this.resource = resource;
    }

    public static ChatLog memory(int sessionCapacity, int messageCapacity) {
        return new ChatLog(new InMemoryAgentSessionRepository(sessionCapacity),
                new InMemoryAgentMessageRepository(messageCapacity), () -> { });
    }

    public static ChatLog file(String path) {
        H2TranscriptStore store = new H2TranscriptStore(path);
        return new ChatLog(store.sessions(), store.messages(), store);
    }

    public AgentSessionRepository sessions() {
        return sessions;
    }

    public AgentMessageRepository messages() {
        return messages;
    }

    @Override
    public void close() throws Exception {
        resource.close();
    }
}
