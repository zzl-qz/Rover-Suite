package com.rover.admin.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rover.admin.security.AgentSecurityService;
import com.rover.agent.core.model.*;
import com.rover.agent.core.port.AgentAccess.Permission;
import com.rover.agent.core.port.RouteControlPort;
import com.rover.agent.runtime.repository.AgentStore;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class IncidentCaseControllerTest {
    @TempDir Path temp;

    @Test void boundsLargeCaseProjectionAndReportsTruncation() throws Exception {
        var scope = new ResourceScope("org", "team", "dev");
        RouteControlPort control = mock(RouteControlPort.class);
        when(control.resourceScope()).thenReturn(scope);
        when(control.resourceNamespace()).thenReturn("dev");
        AgentSecurityService security = mock(AgentSecurityService.class);
        try (AgentStore store = AgentStore.file(temp.resolve("bounded").toString())) {
            store.sessions().save(new Session("s1", "alice", "orders", null, SessionStatus.ACTIVE,
                    1, 9, List.of("i1"), scope, "dev"));
            store.incidents().save(new Incident("i1", "s1", IncidentOrigin.ALERT, "/orders",
                    IncidentStatus.INVESTIGATING, ResourceTarget.route("/orders"), TimeRange.unspecified(), "", 1, 9, List.of()));
            store.sessions().save(new Session("s1", "alice", "orders", "i1", SessionStatus.ACTIVE,
                    1, 9, List.of("i1"), scope, "dev"));
            for (int i = 0; i < 501; i++) {
                store.tasks().save(new TaskView("t" + i, "s1", "i1", TaskStatus.COMPLETED, null, "/orders",
                        ResourceTarget.route("/orders"), "task " + i, i + 1, i + 1, List.of(), null, null, null));
            }
            var result = new IncidentCaseController(store, security, control).get("i1",
                    new UsernamePasswordAuthenticationToken("alice", "n/a")).getBody();
            assertNotNull(result);
            assertTrue(result.truncated());
            assertEquals(500, result.investigations().size());
            assertEquals("t1", result.investigations().get(0).taskId());
        }
    }

    @Test void caseTimelineProjectsPersistedInvestigationsActionsAndEffectsAndHidesOtherUsers() throws Exception {
        var scope = new ResourceScope("org", "team", "dev");
        RouteControlPort control = mock(RouteControlPort.class);
        when(control.resourceScope()).thenReturn(scope);
        when(control.resourceNamespace()).thenReturn("dev");
        AgentSecurityService security = mock(AgentSecurityService.class);
        try (AgentStore store = AgentStore.file(temp.resolve("cases").toString())) {
            store.sessions().save(new Session("s1", "alice", "orders", null, SessionStatus.ACTIVE, 1, 9, List.of("i1"), scope, "dev"));
            store.sessions().save(new Session("s2", "bob", "secret", null, SessionStatus.ACTIVE, 1, 9, List.of("i2"), scope, "dev"));
            store.incidents().save(new Incident("i1", "s1", IncidentOrigin.ALERT, "/orders", IncidentStatus.RESOLVED,
                    ResourceTarget.route("/orders"), TimeRange.unspecified(), "500s after release", 2, 8, List.of("t1")));
            store.incidents().save(new Incident("i2", "s2", IncidentOrigin.USER, "/secret", IncidentStatus.RESOLVED,
                    ResourceTarget.route("/secret"), TimeRange.unspecified(), "private", 2, 8, List.of()));
            store.sessions().save(new Session("s1", "alice", "orders", "i1", SessionStatus.ACTIVE, 1, 9, List.of("i1"), scope, "dev"));
            store.sessions().save(new Session("s2", "bob", "secret", "i2", SessionStatus.ACTIVE, 1, 9, List.of("i2"), scope, "dev"));
            var task = new TaskView("t1", "s1", "i1", TaskStatus.COMPLETED, null, "/orders",
                    ResourceTarget.route("/orders"), "why 500?", 3, 7, List.of(),
                    new InvestigationReport("upstream timeout", Confidence.HIGH, List.of(), List.of(), List.of(), ""), null, null);
            store.tasks().save(task);
            store.tasks().saveEvent(task, new com.rover.agent.core.event.TaskEvent(1, "t1",
                    com.rover.agent.core.event.TaskEventType.TASK_STARTED, 3, java.util.Map.of("status", "RUNNING")));
            store.tasks().saveEvent(task, new com.rover.agent.core.event.TaskEvent(2, "t1",
                    com.rover.agent.core.event.TaskEventType.ANALYSIS_DELTA, 4, java.util.Map.of("text", "private model trace")));
            store.tasks().saveEvent(task, new com.rover.agent.core.event.TaskEvent(3, "t1",
                    com.rover.agent.core.event.TaskEventType.TASK_COMPLETED, 7, java.util.Map.of("summary", "upstream timeout")));
            var action = new AgentAction("a1", "s1", "i1", "t1", ActionType.ADJUST_ROUTE_TARGET_WEIGHT,
                    ActionStatus.SUCCESS, "r1", "/orders", "orders", "v2", 5, 10, 10, WeightRequestUnit.RAW_WEIGHT,
                    5, 10, 1, "operation-1", 2, null, "alice", "approver", 6, List.of("5 → 10"),
                    "canary adjust", null, 4, 6, 0);
            store.actions().save(action);
            store.durable().appendAudit("ACTION_PENDING_APPROVAL", "a1", "alice", "dev", java.util.Map.of("status", "PENDING_APPROVAL"));
            store.durable().appendAudit("ACTION_SUCCESS", "a1", "approver", "dev", java.util.Map.of("status", "SUCCESS"));
            store.effectVerifications().save(new ActionEffectVerification("a1", ActionEffectVerification.Phase.APPLY,
                    1, "a".repeat(64), ActionEffectVerification.State.COMPLETE, ActionEffectVerification.Outcome.RECOVERED,
                    4, 5, 0, 10, 2, 2, java.util.Map.of("errorRate", 0.1), java.util.Map.of("errorRate", 0.01),
                    List.of(), List.of(), 0));
            long reviewCreatedAt = System.currentTimeMillis();
            var retrospective = new IncidentRetrospective("case-i1-review", "i1", scope, "复盘",
                    "现象", "根因", "处置", "恢复", List.of(), "alice", reviewCreatedAt, reviewCreatedAt + 90_000);
            var draft = store.knowledgeDocuments().createIncidentDraft(retrospective);
            store.knowledgeDocuments().transition(draft.documentId(), 1, KnowledgeDocumentVersion.Status.DRAFT,
                    current -> current.reject("reviewer", reviewCreatedAt + 1)).orElseThrow();

            IncidentCaseController controller = new IncidentCaseController(store, security, control);
            var alice = new UsernamePasswordAuthenticationToken("alice", "n/a");
            var result = controller.get("i1", alice);
            assertEquals(200, result.getStatusCode().value());
            assertEquals(1, result.getBody().investigations().size());
            assertEquals(1, result.getBody().actions().size());
            assertEquals(ActionEffectVerification.Outcome.RECOVERED, result.getBody().actions().get(0).applyEffect().outcome());
            assertTrue(result.getBody().timeline().stream().anyMatch(event -> event.kind().equals("TASK_EVENT")
                    && event.status().equals("TASK_STARTED")));
            assertFalse(result.getBody().timeline().stream().anyMatch(event -> event.summary().contains("private model trace")));
            assertTrue(result.getBody().audit().stream().anyMatch(event -> event.kind().equals("INCIDENT_RETROSPECTIVE_DRAFT_CREATED")));
            assertTrue(result.getBody().audit().stream().anyMatch(event -> event.kind().equals("KNOWLEDGE_REJECTED")));
            assertTrue(result.getBody().timeline().stream().anyMatch(event -> event.kind().equals("AUDIT")
                    && event.status().equals("ACTION_SUCCESS")));
            assertEquals(404, controller.get("i2", alice).getStatusCode().value());
            verify(security, times(2)).require("alice", Permission.READ);
        }
    }

    @Test void refusesCaseFromAnotherEnvironmentEvenWhenPrincipalMatches() throws Exception {
        RouteControlPort control = mock(RouteControlPort.class);
        when(control.resourceScope()).thenReturn(new ResourceScope("org", "team", "dev"));
        when(control.resourceNamespace()).thenReturn("dev");
        AgentSecurityService security = mock(AgentSecurityService.class);
        try (AgentStore store = AgentStore.file(temp.resolve("scope").toString())) {
            store.sessions().save(new Session("s1", "alice", "prod", null, SessionStatus.ACTIVE, 1, 1, List.of("i1"),
                    new ResourceScope("org", "team", "prod"), "prod"));
            store.incidents().save(new Incident("i1", "s1", IncidentOrigin.ALERT, "/orders", IncidentStatus.RESOLVED,
                    ResourceTarget.route("/orders"), TimeRange.unspecified(), "", 1, 1, List.of()));
            assertEquals(404, new IncidentCaseController(store, security, control).get("i1",
                    new UsernamePasswordAuthenticationToken("alice", "n/a")).getStatusCode().value());
        }
    }
}
