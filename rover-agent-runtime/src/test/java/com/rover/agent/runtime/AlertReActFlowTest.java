package com.rover.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.capability.CapabilityExecutor;
import com.rover.agent.core.capability.CapabilityRegistry;
import com.rover.agent.core.model.AlertEventState;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.TimeRange;
import com.rover.agent.core.port.LogEntry;
import com.rover.agent.core.port.LogQueryPort;
import com.rover.agent.core.port.LogRequest;
import com.rover.agent.runtime.journal.OpsJournal;
import com.rover.agent.runtime.repository.InMemoryAlertLifecycleRepository;
import com.rover.agent.runtime.task.IncidentRegistry;
import com.rover.agent.runtime.task.InvestigationTaskRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AlertReActFlowTest {
    @Test
    void alertUsesSharedReactLoopWithBoundTargetAndWindow() throws Exception {
        TimeRange range = new TimeRange(100_000L, 200_000L);
        AtomicReference<LogRequest> queried = new AtomicReference<>();
        LogQueryPort logs = request -> {
            queried.set(request);
            return List.of(new LogEntry(150_000L, "error", "/api/orders", "HTTP 503 from orders backend"));
        };
        CapabilityExecutor executor = new CapabilityExecutor(null, null, null, null, null, null, logs, null,
                CapabilityRegistry.standard());
        IncidentRegistry incidents = new IncidentRegistry();
        InvestigationTaskRegistry tasks = new InvestigationTaskRegistry();
        try {
            var loop = ScriptedConversationModel.service(executor, tools -> {
                String evidence = tools.queryLogs("error", "/api/orders", 24);
                assertTrue(evidence.contains("503"));
                return "日志记录了订单接口 503。";
            });
            var service = new InvestigationService(incidents, tasks, loop, OpsJournal.none(), null,
                    new InMemoryAlertLifecycleRepository());
            var accepted = service.submitAlert("monitor", "delivery-1", "orders-5xx", AlertEventState.FIRING,
                    "/api/orders", "5xx rate high", range);
            var done = await(service, accepted.taskId());

            assertEquals("COMPLETED", done.status().name());
            assertNotNull(done.incidentId());
            assertTrue(done.result().summary().contains("503"));
            assertFalse(done.result().evidence().isEmpty());
            assertEquals("/api/orders", queried.get().target());
            assertEquals(range.fromMillis(), queried.get().from());
            assertEquals(range.toMillis(), queried.get().to());
            assertEquals(done.result().summary(), incidents.incident(done.incidentId()).orElseThrow().summary());
        } finally {
            tasks.stop();
        }
    }

    private static com.rover.agent.core.model.TaskView await(InvestigationService service, String taskId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            var task = service.get(taskId);
            if (task.status().terminal()) { return task; }
            Thread.sleep(10);
        }
        throw new AssertionError("告警 ReAct 任务未结束");
    }
}
