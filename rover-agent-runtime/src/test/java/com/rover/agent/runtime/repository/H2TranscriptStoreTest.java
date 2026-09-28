package com.rover.agent.runtime.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.model.AgentMessage;
import com.rover.agent.core.model.MessageRole;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.SessionStatus;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 关掉再打开，会话列表和原话还在，顺序仍是先说的在前。 */
class H2TranscriptStoreTest {

    @Test
    void reopensSessionsAndMessagesInOrder() throws Exception {
        String path = Files.createTempDirectory("rover-chat").resolve("rover").toString();
        Session session = new Session("s1", "admin", "为什么失败", "inc-1", SessionStatus.ACTIVE,
                10L, 20L, List.of("inc-1"));
        try (H2TranscriptStore first = new H2TranscriptStore(path)) {
            first.sessions().save(session);
            first.messages().save(new AgentMessage("m1", "s1", MessageRole.USER, "为什么 /api/demo 失败？", 11L, "t1"));
            first.messages().save(new AgentMessage("m2", "s1", MessageRole.AGENT, "上游没有健康实例。", 12L, "t1"));
        }

        try (H2TranscriptStore second = new H2TranscriptStore(path)) {
            assertEquals(List.of("s1"), second.sessions().findByUserId("admin").stream().map(Session::sessionId).toList());
            assertEquals("为什么失败", second.sessions().find("s1").orElseThrow().title());
            List<AgentMessage> messages = second.messages().bySession("s1");
            assertEquals(List.of("为什么 /api/demo 失败？", "上游没有健康实例。"),
                    messages.stream().map(AgentMessage::content).toList());
            assertEquals("t1", messages.get(0).relatedTaskId());
            assertTrue(second.sessions().findByUserId("other").isEmpty());
        }
    }
}
