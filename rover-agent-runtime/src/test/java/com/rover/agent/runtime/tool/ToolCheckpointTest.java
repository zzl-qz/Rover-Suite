package com.rover.agent.runtime.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.capability.CapabilityExecutor;
import com.rover.agent.core.capability.CapabilityRegistry;
import com.rover.agent.core.model.AgentCheckpoint;
import com.rover.agent.core.model.CheckpointStage;
import com.rover.agent.core.model.TaskStatus;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.port.ConfigReadPort;
import com.rover.agent.core.port.RouteReadPort;
import com.rover.agent.core.snapshot.ConfigEntrySnapshot;
import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.core.snapshot.RouteSnapshot;
import com.rover.agent.runtime.metrics.AgentMetrics;
import com.rover.agent.runtime.repository.InMemoryAgentCheckpointRepository;
import com.rover.agent.runtime.repository.InMemoryAgentTaskRepository;
import com.rover.agent.runtime.task.AgentExecutionSettings;
import com.rover.agent.runtime.task.InvestigationTask;
import com.rover.agent.runtime.task.InvestigationTaskRegistry;
import com.rover.agent.runtime.task.TaskEventBus;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 对话主路径的安全边界：每次工具成功返回后都要推进一个恢复点。
 *
 * <p>守的是 P3.2 的核心语义——工具调用是 Agent 真正产生新事实的地方，所以恢复点落在
 * 「工具返回 + 步骤终态 + 证据落库」之后，而不是「准备调用之前」。崩在调用途中的话恢复点不会前进，
 * 恢复时重做这一次只读调用即可（只读工具重做无副作用）。
 */
class ToolCheckpointTest {

    private final InMemoryAgentCheckpointRepository checkpoints = new InMemoryAgentCheckpointRepository();

    @Test
    void eachCompletedToolAdvancesOneSafeResumePoint() {
        InvestigationTask task = registerTask("tool-checkpoint-1");
        OpsTools tools = new OpsTools(executor(), task);

        tools.listRoutes();
        tools.getConfigs();

        List<AgentCheckpoint> marks = checkpoints.byTask(task.taskId());
        // 任务开始（STARTED）+ 两次工具完成：生命周期点与工具点各自独立，各是一条记录。
        assertEquals(List.of(CheckpointStage.STARTED, CheckpointStage.TOOL_COMPLETED,
                CheckpointStage.TOOL_COMPLETED), marks.stream().map(AgentCheckpoint::stage).toList());
        assertEquals(List.of(1, 2, 3), marks.stream().map(AgentCheckpoint::sequenceNo).toList(),
                "恢复点序号按任务递增，不从零重来");

        AgentCheckpoint latest = marks.get(marks.size() - 1);
        assertEquals(2, latest.toolCallCount(), "工具点数的是已完成的只读调用次数");
        assertEquals(2, latest.lastStepSequence(), "每个工具一条步骤：高水位跟着走");
        assertTrue(latest.lastEvidenceSequence() >= 1, "证据与恢复点同一次写入落库，恢复点不会领先于事实");

        // 两个工具的高水位都如实记下：STARTED 只有 0，第一次工具之后是 1 条步骤。
        assertEquals(1, marks.get(1).lastStepSequence());
        assertEquals(1, marks.get(1).toolCallCount());
    }

    @Test
    void startedCheckpointIsNotRewrittenByLaterProgress() {
        InvestigationTask task = registerTask("tool-checkpoint-2");
        OpsTools tools = new OpsTools(executor(), task);

        AgentCheckpoint started = checkpoints.latest(task.taskId()).orElseThrow();
        assertEquals(CheckpointStage.STARTED, started.stage());
        assertEquals(0, started.toolCallCount());
        assertEquals(0, started.lastStepSequence());
        assertEquals(0, started.lastEvidenceSequence());

        tools.listRoutes();

        // 恢复点是只前不回的轨迹：STARTED 那一行记的是「刚开始时的状态」，不被后来的进展改写。
        assertEquals(started, checkpoints.byTask(task.taskId()).get(0));
        assertEquals(TaskStatus.RUNNING, task.view().status());
    }

    @Test
    void evidenceIsReadableBeforeAnyConclusionExists() {
        InvestigationTask task = registerTask("tool-checkpoint-3");
        OpsTools tools = new OpsTools(executor(), task);

        tools.listRoutes();

        TaskView view = task.view();
        assertNull(view.result(), "任务还在跑：结论还没合成");
        assertFalse(view.evidence().isEmpty(), "但已经取到的事实必须已经在视图里可读");
        assertEquals("Gateway 路由表", view.evidence().get(0).source(), "读回来的就是这次取到的那份快照");
    }

    private InvestigationTask registerTask(String sessionId) {
        InvestigationTaskRegistry registry = new InvestigationTaskRegistry(new InMemoryAgentTaskRepository(50),
                AgentExecutionSettings.defaults(), new TaskEventBus(), AgentMetrics.NOOP, checkpoints);
        InvestigationTask task = registry.register(sessionId, "为什么不通");
        task.start();
        return task;
    }

    private CapabilityExecutor executor() {
        return new CapabilityExecutor(routes(), null, null, null, configs(), null, null, null,
                CapabilityRegistry.standard());
    }

    /** 一条真实路由：让路由工具能产出证据，而不是空快照。 */
    private static RouteReadPort routes() {
        return new RouteReadPort() {
            @Override
            public List<RouteSnapshot> routes() {
                return List.of(new RouteSnapshot("r1", "/api/order", "order-service", "", "", "", 1L));
            }

            @Override
            public DiscoveryMode discoveryMode() {
                return DiscoveryMode.NAMESERVER;
            }
        };
    }

    private static ConfigReadPort configs() {
        return () -> List.<ConfigEntrySnapshot>of();
    }
}
