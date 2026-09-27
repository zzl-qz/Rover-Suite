package com.rover.agent.runtime.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.model.AgentMessage;
import com.rover.agent.core.model.Incident;
import com.rover.agent.core.model.IncidentOrigin;
import com.rover.agent.core.model.IncidentStatus;
import com.rover.agent.core.model.MessageRole;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.SessionStatus;
import com.rover.agent.core.model.TimeRange;
import com.rover.agent.runtime.repository.InMemoryAgentMessageRepository;
import com.rover.agent.runtime.repository.InMemoryAgentSessionRepository;
import com.rover.agent.runtime.repository.InMemoryIncidentRepository;
import com.rover.agent.runtime.repository.StoreCapacityExceededException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 保留策略：容量满时由它决定"丢谁"，并且整组丢。
 *
 * 两条硬规则必须成立：正在跑任务的会话/事件不动；确实腾不出位置时照常抛出「存储已满」，
 * 不能靠丢数据把容量配置错误掩盖过去。
 */
class WorkspaceRetentionTest {

    @Test
    void sessionAdmissionCleansOldestIdleSessionWithItsChildren() {
        Fixture fixture = new Fixture(1, 10, 10);
        fixture.sessions.save(session("s1", 1L));
        fixture.incidents.save(incident("inc-1", "s1", 1L));
        fixture.messages.save(message("m1", "s1", 1L));
        fixture.retirement.retiredTasks.put("s1", 2);

        fixture.retention.admitSession(session("s2", 2L));

        assertEquals(List.of("s2"), fixture.sessions.listAll().stream().map(Session::sessionId).toList());
        // 子记录一起清掉：留下"任务挂着不存在的会话"就是孤儿引用。
        assertTrue(fixture.incidents.bySession("s1").isEmpty());
        assertTrue(fixture.messages.bySession("s1").isEmpty());
        assertEquals(List.of("s1"), fixture.retirement.retiredSessions);
    }

    @Test
    void sessionWithRunningTaskIsKeptAndAdmissionFailsVisibly() {
        Fixture fixture = new Fixture(1, 10, 10);
        fixture.sessions.save(session("s1", 1L));
        fixture.retirement.activeSessions.add("s1");

        // 没有可清理的候选时不能悄悄丢数据：容量配不对必须报出来。
        assertThrows(StoreCapacityExceededException.class, () -> fixture.retention.admitSession(session("s2", 2L)));
        assertEquals(List.of("s1"), fixture.sessions.listAll().stream().map(Session::sessionId).toList());
        assertTrue(fixture.retirement.retiredSessions.isEmpty());
    }

    @Test
    void incidentAdmissionCleansOldestIncidentAndDetachesItFromSession() {
        Fixture fixture = new Fixture(10, 1, 10);
        fixture.sessions.save(new Session("s1", "alice", "标题", "inc-1", SessionStatus.ACTIVE, 1L, 1L,
                List.of("inc-1")));
        fixture.incidents.save(incident("inc-1", "s1", 1L));

        fixture.retention.admitIncident(incident("inc-2", "s1", 2L));

        assertEquals(List.of("inc-2"), fixture.incidents.listAll().stream().map(Incident::incidentId).toList());
        Session session = fixture.sessions.find("s1").orElseThrow();
        // 被清理的事件必须从会话的事件列表与当前事件指针上摘干净。
        assertFalse(session.incidentIds().contains("inc-1"));
        assertNull(session.activeIncidentId());
        assertEquals(List.of("inc-1"), fixture.retirement.retiredIncidents);
    }

    @Test
    void messageAdmissionDropsTheOldestMessageInTheWindow() {
        Fixture fixture = new Fixture(10, 10, 2);
        fixture.retention.admitMessage(message("m1", "s1", 1L));
        fixture.retention.admitMessage(message("m2", "s1", 2L));
        fixture.retention.admitMessage(message("m3", "s2", 3L));

        // 消息是叶子记录：超出窗口就丢最早的一条，与上下文管理器"只取最近若干条"同口径。
        assertEquals(List.of("m2", "m3"), fixture.messages.listAll().stream().map(AgentMessage::messageId).toList());
    }

    /**
     * 一套容量一致的存储与策略：仓储负责"满了拒绝写入"，策略负责"清理后重试"。
     * 生产装配里两者用的是同一批容量常量，测试也必须对齐，否则测不到真实的拒绝路径。
     */
    private static final class Fixture {

        private final InMemoryAgentSessionRepository sessions;
        private final InMemoryIncidentRepository incidents;
        private final InMemoryAgentMessageRepository messages;
        private final FakeRetirement retirement = new FakeRetirement();
        private final WorkspaceRetention retention;

        private Fixture(int sessionCapacity, int incidentCapacity, int messageCapacity) {
            this.sessions = new InMemoryAgentSessionRepository(sessionCapacity);
            this.incidents = new InMemoryIncidentRepository(incidentCapacity);
            this.messages = new InMemoryAgentMessageRepository(messageCapacity);
            this.retention = new WorkspaceRetention(sessions, incidents, messages, retirement,
                    sessionCapacity, incidentCapacity, messageCapacity);
        }
    }

    /** 任务回收口的测试替身：用集合表达"哪些会话/事件下还有任务在跑"，并记录回收调用。 */
    private static final class FakeRetirement implements TaskRetirement {

        private final Set<String> activeSessions = new HashSet<>();
        private final Set<String> activeIncidents = new HashSet<>();
        private final List<String> retiredSessions = new ArrayList<>();
        private final List<String> retiredIncidents = new ArrayList<>();
        private final Map<String, Integer> retiredTasks = new HashMap<>();

        @Override
        public boolean hasActiveTasksInSession(String sessionId) {
            return activeSessions.contains(sessionId);
        }

        @Override
        public boolean hasActiveTasksInIncident(String incidentId) {
            return activeIncidents.contains(incidentId);
        }

        @Override
        public int retireBySession(String sessionId) {
            retiredSessions.add(sessionId);
            return retiredTasks.getOrDefault(sessionId, 0);
        }

        @Override
        public int retireByIncident(String incidentId) {
            retiredIncidents.add(incidentId);
            return retiredTasks.getOrDefault(incidentId, 0);
        }
    }

    private static Session session(String sessionId, long lastActiveAtMillis) {
        return new Session(sessionId, "alice", "标题", null, SessionStatus.ACTIVE, 1L, lastActiveAtMillis,
                List.of());
    }

    private static Incident incident(String incidentId, String sessionId, long createdAtMillis) {
        return new Incident(incidentId, sessionId, IncidentOrigin.USER, "/api/demo/tt",
                IncidentStatus.OPEN, ResourceTarget.route("/api/demo/tt"),
                TimeRange.unspecified(), "", createdAtMillis, createdAtMillis, List.of());
    }

    private static AgentMessage message(String messageId, String sessionId, long createdAtMillis) {
        return new AgentMessage(messageId, sessionId, MessageRole.USER, "内容", createdAtMillis, null);
    }
}