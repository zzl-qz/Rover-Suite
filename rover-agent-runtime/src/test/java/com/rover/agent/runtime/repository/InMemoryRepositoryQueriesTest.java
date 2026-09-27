package com.rover.agent.runtime.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.rover.agent.core.model.TaskStatus;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.model.TimeRange;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 内存仓储的语义化查询与容量语义。
 *
 * 查询按领域含义命名（按用户、按会话、按事件），调用方不再自己 listAll 过滤：
 * 上层读模型（会话列表、级联清理）都建立在这些方法上，实现换成持久化时 SQL 才对得上。
 * 容量满时一律拒绝写入（抛 {@link StoreCapacityExceededException}），绝不静默淘汰——
 * 淘汰由 {@code WorkspaceRetention} 整组做，仓储自己丢记录会留下孤儿引用。
 */
class InMemoryRepositoryQueriesTest {

    @Test
    void sessionsAreQueriedByUser() {
        InMemoryAgentSessionRepository sessions = new InMemoryAgentSessionRepository(10);
        sessions.save(session("s1", "alice"));
        sessions.save(session("s2", "bob"));
        sessions.save(session("s3", null));

        assertEquals(List.of("s1"), idsOf(sessions.findByUserId("alice")));
        assertTrue(sessions.findByUserId("carol").isEmpty());
        // 未启用登录时归属为空：只列出同样没有归属的会话，不会把别人的会话带出来。
        assertEquals(List.of("s3"), idsOf(sessions.findByUserId(null)));
    }

    @Test
    void tasksAreQueriedAndRemovedBySessionAndIncident() {
        InMemoryAgentTaskRepository tasks = new InMemoryAgentTaskRepository(10);
        tasks.save(task("t1", "s1", "inc-1"));
        tasks.save(task("t2", "s1", "inc-2"));
        tasks.save(task("t3", "s2", "inc-2"));

        assertEquals(List.of("t1", "t2"), taskIdsOf(tasks.findBySessionId("s1")));
        assertEquals(List.of("t2", "t3"), taskIdsOf(tasks.findByIncidentId("inc-2")));
        assertEquals(2, tasks.removeByIncident("inc-2"));
        assertEquals(List.of("t1"), taskIdsOf(tasks.listAll()));
        assertEquals(1, tasks.removeBySession("s1"));
        assertEquals(0, tasks.removeBySession("s1"));
    }

    @Test
    void incidentsAreReadBySessionAndRemovedInBulk() {
        InMemoryIncidentRepository incidents = new InMemoryIncidentRepository(10);
        incidents.save(incident("inc-1", "s1"));
        incidents.save(incident("inc-2", "s2"));

        assertEquals(List.of("inc-1"), incidentIdsOf(incidents.bySession("s1")));
        assertEquals(1, incidents.removeBySession("s1"));
        assertTrue(incidents.find("inc-1").isEmpty());
    }

    @Test
    void messagesExposeOldestAndReportRemovalCounts() {
        InMemoryAgentMessageRepository messages = new InMemoryAgentMessageRepository(10);
        messages.save(message("m1", "s1"));
        messages.save(message("m2", "s1"));

        assertEquals("m1", messages.oldest().orElseThrow().messageId());
        assertEquals(List.of("m1", "m2"), messageIdsOf(messages.bySession("s1")));
        assertEquals(2, messages.removeBySession("s1"));
        assertTrue(messages.oldest().isEmpty());
    }

    @Test
    void capacityIsRejectedInsteadOfEvictingExistingRecords() {
        InMemoryAgentSessionRepository sessions = new InMemoryAgentSessionRepository(1);
        sessions.save(session("s1", "alice"));

        assertThrows(StoreCapacityExceededException.class, () -> sessions.save(session("s2", "alice")));
        // 同键更新不受容量影响：会话指针、活动时间这类更新不会因为"满了"而写不进去。
        sessions.save(session("s1", "alice"));
        assertEquals(1, sessions.listAll().size());
    }

    private static Session session(String sessionId, String userId) {
        return new Session(sessionId, userId, "标题", null, SessionStatus.ACTIVE, 1L, 1L, List.of());
    }

    private static TaskView task(String taskId, String sessionId, String incidentId) {
        return new TaskView(taskId, sessionId, incidentId, TaskStatus.COMPLETED, null, "/api/demo/tt",
                ResourceTarget.route("/api/demo/tt"), "为什么失败？", 1L, 2L, List.of(), null, null, null);
    }

    private static Incident incident(String incidentId, String sessionId) {
        return new Incident(incidentId, sessionId, IncidentOrigin.USER, "/api/demo/tt",
                IncidentStatus.OPEN, ResourceTarget.route("/api/demo/tt"),
                TimeRange.unspecified(), "", 1L, 1L, List.of());
    }

    private static AgentMessage message(String messageId, String sessionId) {
        return new AgentMessage(messageId, sessionId, MessageRole.USER, "内容", 1L, null);
    }

    private static List<String> idsOf(List<Session> sessions) {
        return sessions.stream().map(Session::sessionId).toList();
    }

    private static List<String> taskIdsOf(List<TaskView> tasks) {
        return tasks.stream().map(TaskView::taskId).toList();
    }

    private static List<String> incidentIdsOf(List<Incident> incidents) {
        return incidents.stream().map(Incident::incidentId).toList();
    }

    private static List<String> messageIdsOf(List<AgentMessage> messages) {
        return messages.stream().map(AgentMessage::messageId).toList();
    }
}