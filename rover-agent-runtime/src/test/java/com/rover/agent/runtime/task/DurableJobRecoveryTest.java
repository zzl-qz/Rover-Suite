package com.rover.agent.runtime.task;

import static org.junit.jupiter.api.Assertions.*;
import com.rover.agent.core.model.*;
import com.rover.agent.runtime.metrics.AgentMetrics;
import com.rover.agent.runtime.repository.AgentStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DurableJobRecoveryTest {
    @TempDir Path root;
    private static InvestigationTaskRegistry registry(AgentStore store) {
        var result = new InvestigationTaskRegistry(store.tasks(), AgentExecutionSettings.defaults(), new TaskEventBus(),
                AgentMetrics.NOOP, store.checkpoints(), new AgentRunLimits(1, 100, 1000, java.time.Duration.ofMinutes(3), 3));
        result.persistence(store.durable()); return result;
    }
    private static Session session() { return new Session("s1", "alice", "test", null, SessionStatus.ACTIVE, 1, 1, List.of()); }

    @Test void killedAdminRecoversReadJobWithoutResettingBudgetOrEventSequence() throws Exception {
        String path = root.resolve("crash/agent").toString();
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx96m", "-XX:ActiveProcessorCount=2", "-Dfile.encoding=UTF-8", "-Djdk.net.unixdomain.tmpdir=" + System.getProperty("jdk.net.unixdomain.tmpdir"),
                "-cp", System.getProperty("java.class.path"), Crash.class.getName(), path)
                .redirectErrorStream(true).redirectOutput(root.resolve("child.log").toFile()).start();
        assertTrue(child.waitFor(20, TimeUnit.SECONDS));
        assertEquals(73, child.exitValue(), new String(java.nio.file.Files.readAllBytes(root.resolve("child.log")), java.nio.charset.StandardCharsets.UTF_8));
        try (var store = AgentStore.file(path)) {
            var tasks = registry(store);
            CountDownLatch resumed = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            tasks.handler("TEST", (task, payload) -> {
                try {
                    task.start(); task.markRunning();
                    assertEquals(1, task.budget().snapshot().modelCalls());
                    assertEquals(1, task.budget().snapshot().toolCalls());
                    assertEquals(110, task.budget().snapshot().tokens(), "中断模型调用按预留输出保守计费");
                    assertThrows(AgentRunBudget.LimitExceeded.class, () -> task.budget().beginModelCall(1, 10));
                    task.fail("预算已用尽，保留原证据");
                } catch (Throwable error) { failure.set(error); }
                finally { task.clearRunning(); resumed.countDown(); }
            });
            tasks.startRecovery();
            try {
                assertTrue(resumed.await(5, TimeUnit.SECONDS));
                assertNull(failure.get());
                var job = store.durable().pendingJobs();
                assertTrue(job.isEmpty());
                assertEquals(TaskStatus.FAILED, store.tasks().listAll().get(0).status());
                var events = store.tasks().eventsAfter(store.tasks().listAll().get(0).taskId(), 0, 100);
                assertEquals(events.size(), events.stream().map(event -> event.eventId()).distinct().count());
            } finally { tasks.stop(); }
        }
    }

    @Test void oldAttemptCannotSaveBudgetStateOrEvidenceAfterRestart() throws Exception {
        String path = root.resolve("attempt/agent").toString();
        String id;
        try (var store = AgentStore.file(path)) {
            store.sessions().save(session());
            var tasks = registry(store);
            try {
                var task = tasks.register("s1", "test"); id = task.taskId();
                store.durable().enqueueJob(id, "TEST", Map.of());
                assertTrue(store.durable().claimJob(id, 0).isPresent());
            } finally { tasks.stop(); }
        }
        try (var store = AgentStore.file(path)) {
            assertEquals(2, store.durable().claimJob(id, 1).orElseThrow().attempt());
            assertThrows(IllegalStateException.class, () -> store.durable().saveBudget(id, 1, Map.of()));
            assertThrows(IllegalStateException.class, () -> store.durable().withCurrentAttempt(id, 1,
                    () -> store.tasks().remove(id)));
            assertTrue(store.tasks().find(id).isPresent());
        }
    }

    @Test void preventsSecondAdminAndReleasesLockOnClose() throws Exception {
        String path = root.resolve("owner/agent").toString();
        try (var first = AgentStore.file(path)) { assertThrows(IllegalStateException.class, () -> AgentStore.file(path)); }
        try (var reopened = AgentStore.file(path)) { assertTrue(reopened.durable().healthy()); }
    }

    @Test void durableWakeTimeSurvivesRestartAndDoesNotOccupyTheWorkerWhileWaiting() throws Exception {
        String path = root.resolve("wake/agent").toString();
        String delayedId;
        long due = System.currentTimeMillis() + 6000;
        try (var store = AgentStore.file(path)) {
            store.sessions().save(session());
            var tasks = registry(store);
            try {
                delayedId = tasks.register("s1", "later").taskId();
                store.durable().enqueueJob(delayedId, "TEST", Map.of(), due);
                assertTrue(store.durable().claimJob(delayedId, 0).isEmpty());
            } finally { tasks.stop(); }
        }
        try (var store = AgentStore.file(path)) {
            var tasks = registry(store);
            CountDownLatch immediate = new CountDownLatch(1), delayed = new CountDownLatch(1);
            tasks.handler("TEST", (task, payload) -> {
                task.answerRecall("done", List.of());
                if (task.taskId().equals(delayedId)) { delayed.countDown(); } else { immediate.countDown(); }
            });
            store.sessions().save(new Session("s2", "alice", "now", null, SessionStatus.ACTIVE, 1, 1, List.of()));
            tasks.startRecovery();
            try {
                var next = tasks.register("s2", "now");
                tasks.enqueue(next, "TEST", Map.of(), () -> { });
                assertTrue(immediate.await(3, TimeUnit.SECONDS), "等待持久唤醒时间不应阻塞其他 Job");
                assertTrue(delayed.await(10, TimeUnit.SECONDS));
                assertTrue(System.currentTimeMillis() >= due);
            } finally { tasks.stop(); }
        }
    }

    public static class Crash {
        public static void main(String[] args) {
            var store = AgentStore.file(args[0]); store.sessions().save(session());
            var tasks = registry(store);
            tasks.handler("TEST", (task, payload) -> {
                task.start(); task.markRunning(); task.budget().reserveTool(); task.budget().beginModelCall(10, 100);
                task.checkpoint(CheckpointStage.STARTED, 0, 1, "test");
                Runtime.getRuntime().halt(73);
            });
            var task = tasks.register("s1", "test");
            tasks.enqueue(task, "TEST", Map.of(), () -> { });
            try { Thread.sleep(10000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            throw new AssertionError("job did not run");
        }
    }
}
