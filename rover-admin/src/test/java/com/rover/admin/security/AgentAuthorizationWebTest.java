package com.rover.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.when;
import com.rover.agent.core.model.AlertEventState;
import com.rover.agent.core.model.AlertIngestResult;
import com.rover.agent.core.model.TaskStatus;
import com.rover.agent.core.port.RouteControlPort;
import com.rover.agent.runtime.AgentOrchestrator;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"rover.admin.log-store-path=./target/security-web/rover",
        "rover.agent.alerts.owner-user-id=admin"})
@org.springframework.test.context.TestPropertySource(properties="rover.admin.log-store-path=./target/test-data/AgentAuthorizationWebTest/rover")
@AutoConfigureMockMvc
class AgentAuthorizationWebTest {
    @Autowired MockMvc http;
    @Autowired AdminUserStore users;
    @Autowired AgentSecurityService security;
    @Autowired RouteControlPort control;
    @MockitoBean AgentOrchestrator agent;

    @BeforeEach void roles() {
        users.saveAccount("reader", "test-reader-password", Set.of("VIEWER"), control.resourceNamespace(), true);
        users.saveAccount("operator", "test-operator-password", Set.of("OPERATOR"), control.resourceNamespace(), true);
        users.saveAccount("policy", "test-policy-password", Set.of("POLICY_ADMIN"), control.resourceNamespace(), true);
    }

    @Test void rejectsWriteByReaderAndRawWriteByOperator() throws Exception {
        http.perform(post("/api/agent/sessions").with(user("reader")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}")) .andExpect(status().isForbidden());
        http.perform(post("/api/agent/actions/fake/approve").with(user("operator")).with(csrf()))
                .andExpect(status().isForbidden());
        http.perform(post("/api/routes/weight").with(user("operator")).with(csrf()))
                .andExpect(status().isForbidden());
        http.perform(post("/api/agent/security/users").with(user("policy")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}")) .andExpect(status().isForbidden());
    }

    @Test void liveRevocationIgnoresStaleSessionRolesAndResourceScope() throws Exception {
        var stale = user("operator").roles("ADMIN");
        http.perform(get("/api/agent/security/me").with(stale)).andExpect(status().isOk());
        users.saveAccount("operator", null, Set.of("OPERATOR"), control.resourceNamespace(), false);
        http.perform(get("/api/agent/security/me").with(stale)).andExpect(status().isForbidden());
        users.saveAccount("reader", null, Set.of("VIEWER"), "other-environment", true);
        http.perform(get("/api/agent/security/me").with(user("reader"))).andExpect(status().isForbidden());
    }

    @Test void machineTokenIsIngestOnlyRevocableAndDoesNotReplaceBrowserCsrf() throws Exception {
        var issued = security.issue("admin", "prometheus", null);
        users.saveAccount("prometheus", "connector-test-password", Set.of("OPERATOR"), control.resourceNamespace(), true);
        when(agent.ingestAlert("connector:prometheus", "delivery-auth-1", "auth-test", AlertEventState.FIRING,
                "/orders", null, null, null))
                .thenReturn(new AlertIngestResult("s-alert", "i-alert", "t-alert", TaskStatus.PENDING,
                        false, false, 1));
        String bearer = "Bearer " + issued.token();
        http.perform(post("/api/agent/events/ingest").header("Authorization", bearer)
                .header("Idempotency-Key", "delivery-auth-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"/orders\",\"alertKey\":\"auth-test\",\"state\":\"FIRING\"}"))
                .andExpect(status().isAccepted());
        http.perform(get("/api/agent/security/me").header("Authorization", bearer)).andExpect(status().isForbidden());
        http.perform(post("/api/agent/actions/fake/approve").header("Authorization", bearer))
                .andExpect(status().isForbidden());
        http.perform(post("/api/agent/events/ingest").with(user("admin"))
                .header("Idempotency-Key", "delivery-auth-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"/orders\",\"alertKey\":\"auth-test-2\",\"state\":\"FIRING\"}"))
                .andExpect(status().isForbidden());
        assertThat(security.machines("admin").toString()).doesNotContain(issued.token());
        security.revoke("admin", issued.id());
        http.perform(post("/api/agent/events/ingest").header("Authorization", bearer)
                .header("Idempotency-Key", "delivery-auth-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"/orders\",\"alertKey\":\"auth-test\",\"state\":\"FIRING\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void alertIngressRejectsMissingIdempotencyAndLifecycleFields() throws Exception {
        http.perform(post("/api/agent/events/ingest").with(user("admin")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/orders\",\"alertKey\":\"missing-delivery\",\"state\":\"FIRING\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        http.perform(post("/api/agent/events/ingest").with(user("admin")).with(csrf())
                        .header("Idempotency-Key", "delivery-missing-fields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/orders\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test void releasePurposeIsSeparateDurableIdempotentAndRevocable() throws Exception {
        var release = security.issue("admin", "ci-" + System.nanoTime(), null, "RELEASE");
        var alert = security.issue("admin", "alert-" + System.nanoTime(), null);
        String body = "{\"sourceEventId\":\"" + java.util.UUID.randomUUID() + "\",\"releaseId\":\"release-1\",\"artifact\":\"sha256:fixture-build\","
                + "\"serviceName\":\"orders\",\"group\":\"v2\",\"status\":\"SUCCEEDED\",\"occurredAtMillis\":" + System.currentTimeMillis() + "}";
        http.perform(post("/api/agent/releases/ingest").header("Authorization", "Bearer " + alert.token())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        http.perform(post("/api/agent/events/ingest").header("Authorization", "Bearer " + release.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"path\":\"/orders\"}")).andExpect(status().isForbidden());
        http.perform(post("/api/agent/releases/ingest").header("Authorization", "Bearer " + release.token())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.event.subject.services[0]").value("orders"))
                .andExpect(jsonPath("$.event.facts.artifact").value("sha256:fixture-build"));
        http.perform(post("/api/agent/releases/ingest").header("Authorization", "Bearer " + release.token())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));
        http.perform(post("/api/agent/releases/ingest").header("Authorization", "Bearer " + release.token())
                .contentType(MediaType.APPLICATION_JSON).content(body.replace("fixture-build", "different-build"))).andExpect(status().isConflict());
        http.perform(post("/api/agent/releases/ingest").header("Authorization", "Bearer " + release.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"unknown\":\"" + "x".repeat(17000) + "\"}"))
                .andExpect(status().isPayloadTooLarge());
        http.perform(post("/api/agent/releases/ingest").with(user("operator"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        http.perform(post("/api/agent/releases/ingest").with(user("reader")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        http.perform(get("/api/agent/timeline").with(user("reader")).param("targetType", "SERVICE").param("target", "orders")
                .param("fromMillis", Long.toString(System.currentTimeMillis() - 60000)).param("toMillis", Long.toString(System.currentTimeMillis() + 1000)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.events").isArray());
        http.perform(get("/api/agent/timeline").header("Authorization", "Bearer " + release.token())).andExpect(status().isForbidden());
        security.revoke("admin", release.id());
        http.perform(post("/api/agent/releases/ingest").header("Authorization", "Bearer " + release.token())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
    }
}
