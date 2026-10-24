package com.rover.agent.runtime.repository;

import static org.junit.jupiter.api.Assertions.*;
import com.rover.agent.core.event.*;
import com.rover.agent.core.model.*;
import com.rover.agent.core.util.AuditActor;
import com.rover.agent.runtime.task.DurableTaskEvents;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DurableFoundationTest {
    @TempDir Path root;
    private Session session() {
        return new Session("s1", "alice", "incident", null, SessionStatus.ACTIVE, 1, 1, List.of())
                .withScope(new ResourceScope("org", "ops", "production"), "namespace");
    }
    private AgentAction action() {
        return new AgentAction("a1", "s1", null, null, ActionType.ADJUST_ROUTE_TARGET_WEIGHT,
                ActionStatus.PENDING_APPROVAL, "orders", "/orders", "orders", "v2", 5, 20,
                20, WeightRequestUnit.RAW_WEIGHT, 5, 17.4, 7, null, null, null, "alice", null, 0,
                List.of("5 -> 20"), "rollout", null, 1, 1)
                .withResourceRef(new ResourceRef(session().scope(), "gateway-a", "route", "orders", "uid-1", 1));
    }
    private TaskView task(TaskStatus status) {
        return new TaskView("t1", "s1", null, status, null, "/orders", ResourceTarget.route("/orders"),
                "why", 1, status.terminal() ? 3 : 0, List.of(), null, null, null);
    }

    @Test void resourceIdentityAndIndependentAuditSurviveRestartAndSessionDeletion() {
        String path = root.resolve("audit/agent").toString();
        try (var store = new JdbcAgentStore(path); var actor = AuditActor.enter("reviewer")) {
            store.sessions().save(session());
            store.actions().save(action());
            assertEquals("reviewer", store.audit("namespace", 0, 10).get(0).actor());
        }
        try (var store = new JdbcAgentStore(path)) {
            assertEquals(action().resourceRef(), store.actions().find("a1").orElseThrow().resourceRef());
            store.sessions().remove("s1");
            assertTrue(store.actions().find("a1").isEmpty());
            assertEquals(1, store.audit("namespace", 0, 10).size());
            assertTrue(store.audit("other-environment", 0, 10).isEmpty());
        }
    }

    @Test void failedAuditInsertRollsBackBusinessActionAsWell() throws Exception {
        String path = root.resolve("atomic/agent").toString();
        try (var store = new JdbcAgentStore(path)) {
            store.sessions().save(session());
            try (var connection = DriverManager.getConnection("jdbc:h2:file:" + path + ";AUTO_SERVER=TRUE", "sa", "");
                 var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE agent_audit ADD CONSTRAINT fail_audit CHECK(event_kind='impossible')");
                assertThrows(IllegalStateException.class, () -> store.actions().save(action()));
                assertTrue(store.actions().find("a1").isEmpty(), "审计失败时不能单独提交业务状态");
            }
        }
    }

    @Test void eventOutboxReplaysOnlyMissingEventsAndKeepsStreamTextAcrossRestart() throws Exception {
        String path = root.resolve("events/agent").toString();
        try (var store = new JdbcAgentStore(path)) {
            store.sessions().save(session());
            store.tasks().saveEvent(task(TaskStatus.RUNNING), new TaskEvent(1, "t1", TaskEventType.TASK_STARTED, 1, Map.of()));
            store.tasks().saveEvent(task(TaskStatus.RUNNING), new TaskEvent(2, "t1", TaskEventType.ANALYSIS_DELTA, 2, Map.of("text", "evidence")));
            store.tasks().saveEvent(task(TaskStatus.COMPLETED), new TaskEvent(3, "t1", TaskEventType.TASK_COMPLETED, 3, Map.of()));
        }
        try (var store = new JdbcAgentStore(path)) {
            assertEquals("evidence", store.tasks().streamSnapshot("t1").orElseThrow().analysis());
            var received = new CopyOnWriteArrayList<TaskEvent>();
            CountDownLatch completed = new CountDownLatch(1);
            var subscription = DurableTaskEvents.subscribe(store.tasks(), task(TaskStatus.COMPLETED), 1, event -> {
                received.add(event); if (event.type().terminal()) { completed.countDown(); }
            });
            assertTrue(completed.await(3, TimeUnit.SECONDS));
            subscription.cancel();
            assertEquals(List.of(2L, 3L), received.stream().map(TaskEvent::eventId).toList());
        }
    }

    @Test void onlineBackupRestoresConsistentSessionsIdentityAuditAndEventCursor() throws Exception {
        Path backup = root.resolve("backup.zip");
        try (var source = new JdbcAgentStore(root.resolve("source/agent").toString())) {
            source.sessions().save(session()); source.actions().save(action());
            source.tasks().saveEvent(task(TaskStatus.COMPLETED), new TaskEvent(1, "t1", TaskEventType.TASK_COMPLETED, 1, Map.of()));
            source.backup(backup);
            assertThrows(IllegalArgumentException.class, () -> source.backup(backup));
        }
        Path destination = root.resolve("restored");
        org.h2.tools.Restore.execute(backup.toString(), destination.toString(), "agent");
        try (var restored = new JdbcAgentStore(destination.resolve("agent").toString())) {
            assertEquals(session(), restored.sessions().find("s1").orElseThrow());
            assertEquals(action().resourceRef(), restored.actions().find("a1").orElseThrow().resourceRef());
            assertEquals(1, restored.audit("namespace", 0, 10).size());
            assertEquals(1, restored.tasks().eventsAfter("t1", 0, 10).size());
            assertTrue(restored.healthy());
        }
    }

    @Test void outboxFailureRollsBackTheTaskSnapshot() {
        try (var store = new JdbcAgentStore(root.resolve("outbox/agent").toString())) {
            store.sessions().save(session());
            var original = new TaskEvent(1, "t1", TaskEventType.TASK_STARTED, 1, Map.of());
            store.tasks().saveEvent(task(TaskStatus.RUNNING), original);
            assertThrows(IllegalStateException.class, () -> store.tasks().saveEvent(task(TaskStatus.COMPLETED), original));
            assertEquals(TaskStatus.RUNNING, store.tasks().find("t1").orElseThrow().status());
            assertEquals(1, store.tasks().eventsAfter("t1", 0, 10).size());
        }
    }

    @Test void resourceNotesDoNotFollowSameNamedReplacementOrDifferentScope() {
        try (var store = new JdbcAgentStore(root.resolve("notes/agent").toString())) {
            var uid = new java.util.concurrent.atomic.AtomicReference<>("uid-1");
            store.saveNote(new com.rover.agent.core.journal.ResourceNote("prod:uid-1", "errors", "old confirmed cause", "logs", "", "s", "t", 1));
            var journal = com.rover.agent.runtime.journal.OpsJournal.of(store).scoped("prod").identified(key -> uid.get());
            assertTrue(journal.recall("/orders", null).contains("old confirmed cause"));
            uid.set("uid-2"); assertEquals("", journal.recall("/orders", null));
            assertEquals("", com.rover.agent.runtime.journal.OpsJournal.of(store).scoped("staging").identified(key -> "uid-1").recall("/orders", null));
        }
    }
}
