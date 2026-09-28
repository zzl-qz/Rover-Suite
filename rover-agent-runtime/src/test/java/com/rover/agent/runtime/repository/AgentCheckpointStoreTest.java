package com.rover.agent.runtime.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.model.AgentCheckpoint;
import com.rover.agent.core.model.AgentStepType;
import com.rover.agent.core.model.CheckpointStage;
import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.EvidenceType;
import com.rover.agent.core.model.Incident;
import com.rover.agent.core.model.IncidentOrigin;
import com.rover.agent.core.model.IncidentStatus;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.SessionStatus;
import com.rover.agent.core.model.Step;
import com.rover.agent.core.model.StepStatus;
import com.rover.agent.core.model.TaskStatus;
import com.rover.agent.core.model.TaskType;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.model.TimeRange;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 安全恢复点的落库语义。
 *
 * <p>三条要点：恢复点与「任务 + 步骤 + 证据」同一次写入（所以高水位不会领先于事实）；
 * 结论还没合成时，已经取到的事实也能读回来（人工会话重启后靠它知道自己查到过什么）；
 * 重启把执行中的任务标成中断，并把最近的安全恢复点写进说明，好让人一眼看出能接着跑到哪。
 */
class AgentCheckpointStoreTest {

    @Test
    void midRunEvidenceAndHighWaterExistBeforeAnyConclusion() throws Exception {
        String path = tempPath("rover-checkpoint-midrun");
        Evidence evidence = evidence("t1", "路由匹配");
        TaskView running = runningTask("t1", List.of(step("st1")), List.of(evidence));
        AgentCheckpoint mark = mark(running, 1, CheckpointStage.TOOL_COMPLETED, 0, 1);

        try (JdbcAgentStore store = new JdbcAgentStore(path)) {
            openSession(store, "s1");
            store.checkpoints().save(running, mark);
        }

        try (JdbcAgentStore store = new JdbcAgentStore(path)) {
            TaskView reloaded = store.tasks().find("t1").orElseThrow();
            assertNull(reloaded.result(), "结论还没合成");
            assertEquals(List.of(evidence), reloaded.evidence(), "但工具取到的事实已经在库里、也读得回来");
            assertEquals(List.of(step("st1")), reloaded.steps());

            AgentCheckpoint latest = store.checkpoints().latest("t1").orElseThrow();
            assertEquals(mark, latest);
            assertEquals(1, latest.lastStepSequence());
            assertEquals(1, latest.lastEvidenceSequence());
            assertEquals(1, latest.toolCallCount());
        }
    }

    @Test
    void restartTurnsInFlightTasksIntoInterruptedOnesPointingAtTheirLastSafePoint() throws Exception {
        String path = tempPath("rover-checkpoint-restart");
        Evidence evidence = evidence("t1", "路由匹配");
        TaskView running = runningTask("t1", List.of(step("st1")), List.of(evidence));

        try (JdbcAgentStore store = new JdbcAgentStore(path)) {
            openSession(store, "s1");
            store.checkpoints().save(running, mark(running, 1, CheckpointStage.PLANNED, 1, 0));
            store.checkpoints().save(running, mark(running, 2, CheckpointStage.TOOL_COMPLETED, 1, 1));
        }

        try (JdbcAgentStore store = new JdbcAgentStore(path)) {
            TaskView interrupted = store.tasks().find("t1").orElseThrow();
            assertEquals(TaskStatus.INTERRUPTED, interrupted.status());
            assertTrue(interrupted.error().contains("重启"), "说清是重启打断的");
            assertTrue(interrupted.error().contains("TOOL_COMPLETED"),
                    "错误说明要指出最近的安全恢复点，而不是一句含糊的失败");
            assertEquals(List.of(evidence), interrupted.evidence(), "已采集的事实不因中断而丢");
            assertEquals(2, store.checkpoints().byTask("t1").size(), "恢复点本身保留：中断不是清空");

            // 两个恢复点按序号排好：恢复从后往前读，第一条是「计划已定稿」。
            assertEquals(List.of(1, 2), store.checkpoints().byTask("t1").stream()
                    .map(AgentCheckpoint::sequenceNo).toList());
        }
    }

    @Test
    void checkpointsGoAwayWithTheirTask() throws Exception {
        String path = tempPath("rover-checkpoint-cascade");
        TaskView running = runningTask("t1", List.of(step("st1")), List.of(evidence("t1", "路由匹配")));

        try (JdbcAgentStore store = new JdbcAgentStore(path)) {
            openSession(store, "s1");
            store.checkpoints().save(running, mark(running, 1, CheckpointStage.TOOL_COMPLETED, 0, 1));
            assertEquals(1, store.checkpoints().byTask("t1").size());

            store.tasks().remove("t1");

            assertTrue(store.checkpoints().byTask("t1").isEmpty(), "任务被回收时它的恢复点跟着走");
            assertEquals(0, store.checkpoints().removeByTask("t1"), "外键已经删干净，再删没有可删的");
        }
    }

    private static AgentCheckpoint mark(TaskView task, int sequence, CheckpointStage stage, int round, int calls) {
        return new AgentCheckpoint("cp-" + sequence, task.taskId(), sequence, stage, round, calls,
                task.steps().size(), task.evidence().size(), "", "execute", 900L + sequence);
    }

    private static TaskView runningTask(String taskId, List<Step> steps, List<Evidence> evidence) {
        return new TaskView(taskId, "s1", "i1", TaskStatus.RUNNING, AgentStepType.ROUTE_INVESTIGATION, "/api/order",
                ResourceTarget.route("/api/order"), "为什么失败？", 80L, 0L, steps, null, null, null,
                TaskType.INVESTIGATION, null, List.of(), null, evidence);
    }

    private static Evidence evidence(String taskId, String title) {
        return Evidence.of(taskId, EvidenceType.ROUTE, "Gateway 路由表", title, "命中 /api/order", "/api/routes",
                Map.of("sampleSize", "1"), 70L);
    }

    private static Step step(String stepId) {
        return new Step(stepId, "t1", AgentStepType.ROUTE_INVESTIGATION, "读取路由", StepStatus.COMPLETED,
                "查 /api/order", "命中", 50L, 60L, null);
    }

    private static void openSession(JdbcAgentStore store, String sessionId) {
        store.sessions().save(new Session(sessionId, "alice", "标题", null, SessionStatus.ACTIVE, 10L, 20L,
                List.of()));
        store.incidents().save(new Incident("i1", sessionId, IncidentOrigin.USER, "/api/order",
                IncidentStatus.INVESTIGATING, ResourceTarget.route("/api/order"), TimeRange.unspecified(), "", 30L,
                40L, List.of()));
        store.sessions().save(new Session(sessionId, "alice", "标题", "i1", SessionStatus.ACTIVE, 10L, 20L,
                List.of()));
    }

    private static String tempPath(String prefix) throws Exception {
        return Files.createTempDirectory(prefix).resolve("rover").toString();
    }
}
