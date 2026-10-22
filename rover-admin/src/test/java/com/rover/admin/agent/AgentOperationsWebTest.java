package com.rover.admin.agent;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rover.admin.client.ManageHttpClient;
import com.rover.admin.security.AdminUserStore;
import com.rover.admin.security.AgentSecurityService;
import com.rover.agent.core.port.RouteControlPort;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "rover.admin.log-store-path=./target/test-data/AgentOperationsWebTest/rover")
@AutoConfigureMockMvc
class AgentOperationsWebTest {
    @Autowired MockMvc http;
    @Autowired AdminUserStore users;
    @Autowired AgentSecurityService security;
    @Autowired RouteControlPort control;
    @MockitoBean ManageHttpClient manageHttpClient;

    @BeforeEach void accounts() {
        users.saveAccount("ops-admin", "test-password", Set.of("ADMIN"), control.resourceNamespace(), true);
        users.saveAccount("ops-viewer", "test-password", Set.of("VIEWER"), control.resourceNamespace(), true);
        users.saveAccount("ops-approver", "test-password", Set.of("APPROVER"), control.resourceNamespace(), true);
        security.stopWrites("ops-admin", false);
    }

    @Test void statusAndEmergencyWriteStopAreRestrictedToSystemManagers() throws Exception {
        http.perform(get("/api/agent/operations")).andExpect(status().isUnauthorized());
        http.perform(get("/api/agent/operations").with(user("ops-viewer"))).andExpect(status().isForbidden());
        http.perform(post("/api/agent/operations/write-stop").with(user("ops-approver")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stopped\":true}"))
                .andExpect(status().isForbidden());
        http.perform(post("/api/agent/operations/write-stop").with(user("ops-admin")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stopped\":true}"))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.writesStopped").value(true));
        http.perform(get("/api/agent/operations").with(user("ops-admin")))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.writesStopped").value(true));
    }

    @Test void reconciliationQueueRequiresManagerPermission() throws Exception {
        http.perform(get("/api/agent/actions/reconciliation").with(user("ops-approver"))).andExpect(status().isForbidden());
        http.perform(get("/api/agent/actions/reconciliation").with(user("ops-admin")))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json("[]"));
    }
}
