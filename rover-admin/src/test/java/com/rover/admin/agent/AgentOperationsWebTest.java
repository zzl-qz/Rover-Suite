package com.rover.admin.agent;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rover.admin.client.ManageHttpClient;
import com.rover.admin.security.AdminUserStore;
import com.rover.admin.security.AgentSecurityService;
import com.rover.agent.core.model.AutoExecutionPolicy;
import com.rover.agent.core.model.ResourceRef;
import com.rover.agent.core.model.ResourceScope;
import com.rover.agent.runtime.repository.AgentStore;
import com.rover.agent.core.port.RouteControlPort;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "rover.admin.log-store-path=./target/test-data/AgentOperationsWebTest/rover")
@AutoConfigureMockMvc
class AgentOperationsWebTest {
    @Autowired MockMvc http;
    @Autowired AdminUserStore users;
    @Autowired AgentSecurityService security;
    @Autowired AgentStore store;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean AgentStore storeSpy;
    @Autowired RouteControlPort control;
    @MockitoBean ManageHttpClient manageHttpClient;
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_TEST_SESSION = new java.util.concurrent.atomic.AtomicInteger();

    @BeforeEach void accounts() {
        users.saveAccount("ops-admin", "test-password", Set.of("ADMIN"), control.resourceNamespace(), true);
        users.saveAccount("ops-admin-2", "another-test-password", Set.of("ADMIN"), control.resourceNamespace(), true);
        users.saveAccount("ops-viewer", "test-password", Set.of("VIEWER"), control.resourceNamespace(), true);
        users.saveAccount("ops-approver", "test-password", Set.of("APPROVER"), control.resourceNamespace(), true);
        security.stopWrites("ops-admin", true);
        security.approveWriteResume("ops-admin", 0);
        security.approveWriteResume("ops-admin-2", 0);
    }

    @Test void statusAndEmergencyWriteStopAreRestrictedToSystemManagers() throws Exception {
        http.perform(get("/api/agent/operations")).andExpect(status().isUnauthorized());
        http.perform(get("/api/agent/operations").with(user("ops-viewer"))).andExpect(status().isForbidden());
        http.perform(post("/api/agent/operations/write-stop").with(user("ops-approver")).with(csrf()))
                .andExpect(status().isForbidden());
        http.perform(post("/api/agent/operations/write-stop").with(user("ops-admin")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.writesStopped").value(true));
    }

    @Test void writeResumeRequiresTwoDifferentCurrentSystemManagers() throws Exception {
        security.stopWrites("ops-admin", true);
        http.perform(post("/api/agent/operations/resume-approval").with(user("ops-viewer")).with(csrf()))
                .andExpect(status().isForbidden());
        http.perform(post("/api/agent/operations/resume-approval").with(user("ops-admin")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.writesStopped").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.writeResume.approvals").value(1));
        http.perform(post("/api/agent/operations/resume-approval").with(user("ops-admin")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.writesStopped").value(true));
        http.perform(post("/api/agent/operations/resume-approval").with(user("ops-admin-2")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.writesStopped").value(false));
    }

    @Test void reconciliationQueueRequiresManagerPermission() throws Exception {
        http.perform(get("/api/agent/actions/reconciliation").with(user("ops-approver"))).andExpect(status().isForbidden());
        http.perform(get("/api/agent/actions/reconciliation").with(user("ops-admin")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json("[]"));
    }

    @Test void autonomyHealthIsVisibleOnlyToPolicyManagers() throws Exception {
        http.perform(get("/api/agent/policies/autonomous/health").with(user("ops-viewer")))
                .andExpect(status().isForbidden());
        http.perform(get("/api/agent/policies/autonomous/health").with(user("ops-admin")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.healthy").value(false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.mode").value("REQUIRE_APPROVAL"));
        long now = System.currentTimeMillis();
        var resource = new ResourceRef(control.resourceScope(), "gateway-health", "GATEWAY_ROUTE",
                "orders-health", "uid-health", 1);
        var draft = AutoExecutionPolicy.create("health-policy", control.resourceNamespace(), resource,
                "orders-v2", "canary", 5, 5, 10, java.time.Duration.ofMinutes(15).toMillis(),
                now + 86_400_000, "ops-admin", now);
        store.autoExecutionPolicies().save(draft, 0);
        store.autoExecutionPolicies().save(draft.revise(true, 5, 5, draft.expiresAtMillis(), "ops-admin", now + 1),
                draft.version());
        http.perform(get("/api/agent/policies/autonomous/health").with(user("ops-admin")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.healthy").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.mode").value("PREAUTHORIZED"));
        security.stopWrites("ops-admin", true);
        http.perform(get("/api/agent/policies/autonomous/health").with(user("ops-admin")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.healthy").value(false));
    }

    @Test void resumeApprovalRejectsOutstandingActionsAndUnhealthyStorage() throws Exception {
        long now = System.currentTimeMillis();
        String sessionId = "pending-session-" + NEXT_TEST_SESSION.incrementAndGet();
        String actionId = "pending-action-" + java.util.UUID.randomUUID();
        store.sessions().save(new com.rover.agent.core.model.Session(sessionId, "ops-admin", "test incident",
                null, com.rover.agent.core.model.SessionStatus.ACTIVE, now, now, java.util.List.of(),
                control.resourceScope(), control.resourceNamespace()));
        store.actions().save(new com.rover.agent.core.model.AgentAction(actionId, sessionId, null, null,
                com.rover.agent.core.model.ActionType.ADJUST_ROUTE_TARGET_WEIGHT, com.rover.agent.core.model.ActionStatus.UNCERTAIN,
                "orders", "/orders", "orders-v2", "canary", 10, 0, 0,
                com.rover.agent.core.model.WeightRequestUnit.RAW_WEIGHT, 10, 0, 1, "original-operation-id",
                null, null, "ops-admin", null, 0, java.util.List.of(), "test", "unknown", now, now));
        org.junit.jupiter.api.Assertions.assertTrue(store.actions().bySession(sessionId).stream()
                .anyMatch(action -> actionId.equals(action.actionId())
                        && action.status() == com.rover.agent.core.model.ActionStatus.UNCERTAIN));
        var pendingSession = store.sessions().find(sessionId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(control.resourceNamespace(), pendingSession.targetNamespace());
        org.junit.jupiter.api.Assertions.assertEquals(control.resourceScope(), pendingSession.scope());
        org.junit.jupiter.api.Assertions.assertEquals(control.resourceNamespace(), pendingSession.targetNamespace());
        org.junit.jupiter.api.Assertions.assertTrue(store.sessions().listAll().stream()
                .filter(session -> java.util.Objects.equals(control.resourceNamespace(), session.targetNamespace())
                        && java.util.Objects.equals(control.resourceScope(), session.scope()))
                .flatMap(session -> store.actions().bySession(session.sessionId()).stream())
                .anyMatch(action -> actionId.equals(action.actionId())
                        && action.status() == com.rover.agent.core.model.ActionStatus.UNCERTAIN));
        security.stopWrites("ops-admin", true);
        http.perform(post("/api/agent/operations/resume-approval").with(user("ops-admin")).with(csrf()))
                .andExpect(status().isConflict());
        store.sessions().remove(sessionId);
        org.mockito.Mockito.doReturn(false).when(storeSpy).persistent();
        http.perform(post("/api/agent/operations/resume-approval").with(user("ops-admin")).with(csrf()))
                .andExpect(status().isServiceUnavailable());
        assertTrue(security.writesStopped());
    }

    @Test void emergencyStopSerializesPolicyEnableAndDoesNotReenableItOnResume() throws Exception {
        long now = System.currentTimeMillis();
        String policyId = "stop-policy-" + java.util.UUID.randomUUID();
        var resource = new ResourceRef(new ResourceScope("org", "ops", "prod"), "gateway-a",
                "GATEWAY_ROUTE", "orders", "route-uid", 1);
        var draft = AutoExecutionPolicy.create(policyId, control.resourceNamespace(), resource,
                "orders-v2", "canary", 5, 5, 10, java.time.Duration.ofMinutes(15).toMillis(),
                now + 86_400_000, "ops-admin", now);
        store.autoExecutionPolicies().save(draft, 0);
        var enabled = draft.revise(true, draft.maxBeforeTrafficPercent(), draft.maxTrafficShiftPercent(),
                draft.expiresAtMillis(), "ops-admin", now + 1);
        store.autoExecutionPolicies().save(enabled, draft.version());
        security.stopWrites("ops-admin", true);
        http.perform(post("/api/agent/policies/autonomous/" + policyId + "/enable")
                        .with(user("ops-admin")).with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}"))
                .andExpect(status().isConflict());
        assertFalse(store.autoExecutionPolicies().find(policyId).orElseThrow().enabled());
        security.approveWriteResume("ops-admin", 0);
        security.approveWriteResume("ops-admin-2", 0);
        assertFalse(store.autoExecutionPolicies().find(policyId).orElseThrow().enabled(),
                "恢复写入不能重启停写前已停用的授权");
    }
}
