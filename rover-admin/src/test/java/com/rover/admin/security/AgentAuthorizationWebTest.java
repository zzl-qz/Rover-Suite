package com.rover.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
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
        String bearer = "Bearer " + issued.token();
        http.perform(post("/api/agent/events/ingest").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"path\":\"/orders\"}"))
                .andExpect(status().isAccepted());
        http.perform(get("/api/agent/security/me").header("Authorization", bearer)).andExpect(status().isForbidden());
        http.perform(post("/api/agent/actions/fake/approve").header("Authorization", bearer))
                .andExpect(status().isForbidden());
        http.perform(post("/api/agent/events/ingest").with(user("admin"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"path\":\"/orders\"}"))
                .andExpect(status().isForbidden());
        assertThat(security.machines("admin").toString()).doesNotContain(issued.token());
        security.revoke("admin", issued.id());
        http.perform(post("/api/agent/events/ingest").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"path\":\"/orders\"}"))
                .andExpect(status().isUnauthorized());
    }
}
