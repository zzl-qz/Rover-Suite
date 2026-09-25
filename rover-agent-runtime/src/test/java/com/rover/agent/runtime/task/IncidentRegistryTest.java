package com.rover.agent.runtime.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.model.Incident;
import com.rover.agent.core.model.IncidentOrigin;
import com.rover.agent.core.model.Session;
import java.util.List;
import org.junit.jupiter.api.Test;

class IncidentRegistryTest {

    @Test
    void incidentCarriesSessionOriginTargetAndTasks() {
        IncidentRegistry registry = new IncidentRegistry();

        Session session = registry.openSession();
        Incident incident = registry.openIncident(session.sessionId(), IncidentOrigin.USER, "/api/demo/tt");
        registry.attachTask(incident.incidentId(), "task-1");

        assertEquals("/api/demo/tt", incident.targetPath());
        assertEquals(IncidentOrigin.USER, incident.origin());
        assertEquals(session.sessionId(), incident.sessionId());
        assertEquals(java.util.List.of("task-1"), registry.incident(incident.incidentId()).orElseThrow().taskIds());
        assertTrue(registry.session(session.sessionId()).orElseThrow().incidentIds().contains(incident.incidentId()));
    }

    @Test
    void incidentAndSessionCountsAreBounded() {
        IncidentRegistry registry = new IncidentRegistry();
        Session first = registry.openSession();
        for (int i = 0; i < 150; i++) {
            registry.openSession();
        }

        assertFalse(registry.session(first.sessionId()).isPresent());
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
        Incident incident = registry.openIncident(session.sessionId(), IncidentOrigin.USER, "/api/demo/tt");

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