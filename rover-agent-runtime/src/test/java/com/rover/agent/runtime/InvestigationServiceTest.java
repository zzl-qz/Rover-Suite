package com.rover.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rover.agent.core.capability.CapabilityExecutor;
import com.rover.agent.core.capability.CapabilityRegistry;
import com.rover.agent.core.port.ConfigReadPort;
import com.rover.agent.core.port.EventReadPort;
import com.rover.agent.core.port.InstanceReadPort;
import com.rover.agent.core.port.MetricReadPort;
import com.rover.agent.core.port.RouteReadPort;
import com.rover.agent.core.port.TraceReadPort;
import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.runtime.journal.OpsJournal;
import com.rover.agent.runtime.task.AgentExecutionSettings;
import com.rover.agent.runtime.task.IncidentRegistry;
import com.rover.agent.runtime.task.InvestigationTaskRegistry;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InvestigationServiceTest {
    private RouteReadPort routes;
    private IncidentRegistry incidents;
    private InvestigationTaskRegistry tasks;
    private InvestigationService service;

    @BeforeEach
    void setUp() {
        routes = mock(RouteReadPort.class);
        var instances = mock(InstanceReadPort.class);
        var metrics = mock(MetricReadPort.class);
        var traces = mock(TraceReadPort.class);
        ConfigReadPort configs = List::of;
        EventReadPort events = List::of;
        incidents = new IncidentRegistry();
        tasks = new InvestigationTaskRegistry();
        service = service(incidents, tasks, routes, instances, metrics, traces, configs, events);
        when(routes.discoveryMode()).thenReturn(DiscoveryMode.NAMESERVER);
    }

    @AfterEach
    void tearDown() { tasks.stop(); }

    @Test
    void alertInvestigationUsesSharedReactLoop() throws Exception {
        var submitted = service.submitAlert("/api/orders", "订单接口告警", null);
        var done = await(service, submitted.taskId());

        assertEquals("COMPLETED", done.status().name());
        assertTrue(done.result().summary().contains("已按证据"));
        assertTrue(done.executedCapabilities().contains(com.rover.agent.core.capability.AgentCapability.INSTANCE_QUERY));
        assertEquals(done.result().summary(), incidents.incident(done.incidentId()).orElseThrow().summary());
    }

    @Test
    void queueRejectionRollsBackSessionAndIncident() {
        var registry = new InvestigationTaskRegistry(new com.rover.agent.runtime.repository.InMemoryAgentTaskRepository(1),
                new AgentExecutionSettings(1, 1, 1));
        try {
            registry.register("occupied", "占位");
            var isolated = service(new IncidentRegistry(), registry, routes, mock(InstanceReadPort.class),
                    mock(MetricReadPort.class), mock(TraceReadPort.class), List::of, List::of);
            assertThrows(RejectedExecutionException.class, () -> isolated.submitAlert("/api/orders", "告警", null));
        } finally { registry.stop(); }
    }

    private static InvestigationService service(IncidentRegistry incidents, InvestigationTaskRegistry tasks,
            RouteReadPort routes, InstanceReadPort instances, MetricReadPort metrics, TraceReadPort traces,
            ConfigReadPort configs, EventReadPort events) {
        var executor = new CapabilityExecutor(routes, instances, metrics, traces, configs, events, request -> List.of(),
                (question, limit) -> List.of(), CapabilityRegistry.standard());
        return new InvestigationService(incidents, tasks, ScriptedConversationModel.service(executor, tools -> {
            tools.listInstances("orders");
            return "已按证据完成告警调查。";
        }), OpsJournal.none(), null, null);
    }

    private static com.rover.agent.core.model.TaskView await(InvestigationService service, String id) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            var task = service.get(id);
            if (task.status().terminal()) return task;
            Thread.sleep(10);
        }
        throw new AssertionError("告警任务未结束");
    }
}
