package com.rover.agent.runtime.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.model.Incident;
import com.rover.agent.core.model.IncidentOrigin;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.TargetType;
import com.rover.agent.runtime.repository.StoreCapacityExceededException;
import org.junit.jupiter.api.Test;

class IncidentRegistryTest {

    @Test
    void incidentCarriesSessionOriginTargetAndTasks() {
        IncidentRegistry registry = new IncidentRegistry();

        Session session = registry.openSession();
        Incident incident = registry.openIncident(session.sessionId(), IncidentOrigin.USER,
                ResourceTarget.route("/api/demo/tt"));
        registry.attachTask(incident.incidentId(), "task-1");

        assertEquals(TargetType.ROUTE, incident.target().type());
        assertEquals("/api/demo/tt", incident.target().value());
        assertEquals(IncidentOrigin.USER, incident.origin());
        assertEquals(session.sessionId(), incident.sessionId());
        assertEquals(java.util.List.of("task-1"), registry.incident(incident.incidentId()).orElseThrow().taskIds());
        assertTrue(registry.session(session.sessionId()).orElseThrow().incidentIds().contains(incident.incidentId()));
        // 新开的事件即成为会话的当前事件：连续追问默认落到它上面。
        assertEquals(incident.incidentId(), registry.session(session.sessionId()).orElseThrow().activeIncidentId());
    }

    @Test
    void sessionStoreRejectsWhenFullInsteadOfEvictingSilently() {
        IncidentRegistry registry = new IncidentRegistry();
        Session first = registry.openSession();
        for (int i = 1; i < 100; i++) {
            registry.openSession();
        }

        // 满则拒绝写入，不静默淘汰：清理谁、要不要连子记录一起清，由保留策略决定
        // （生产装配见 WorkspaceRetention；本类不带保留策略，因此这里看到的是拒绝语义）。
        assertThrows(StoreCapacityExceededException.class, registry::openSession);
        assertTrue(registry.session(first.sessionId()).isPresent(), "已登记的会话不应被悄悄丢掉");
    }

    @Test
    void unknownIdsReturnEmptyInsteadOfFailing() {
        IncidentRegistry registry = new IncidentRegistry();

        assertTrue(registry.incident("missing").isEmpty());
        assertTrue(registry.session("missing").isEmpty());
    }

    @Test
    void removedIncidentDetachesFromItsSession() {
        IncidentRegistry registry = new IncidentRegistry();
        Session session = registry.openSession();
        Incident incident = registry.openIncident(session.sessionId(), IncidentOrigin.USER,
                ResourceTarget.route("/api/demo/tt"));

        registry.removeIncident(incident.incidentId());

        assertTrue(registry.incident(incident.incidentId()).isEmpty());
        // 会话仍在，但事件列表里不能再留下不存在的 ID。
        assertTrue(registry.session(session.sessionId()).isPresent());
        assertTrue(registry.session(session.sessionId()).orElseThrow().incidentIds().isEmpty());
    }

    @Test
    void removedSessionIsGoneAndRemovalIsIdempotent() {
        IncidentRegistry registry = new IncidentRegistry();
        Session session = registry.openSession();

        registry.removeSession(session.sessionId());

        assertTrue(registry.session(session.sessionId()).isEmpty());
        registry.removeSession(session.sessionId());
        registry.removeIncident("missing");
    }
}