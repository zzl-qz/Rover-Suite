package com.rover.admin.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.rover.admin.config.AdminProperties;
import com.rover.agent.core.port.AgentAccess;
import com.rover.agent.core.port.RouteControlPort;
import com.rover.agent.runtime.repository.AgentStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class AgentSecurityServiceTest {
    @TempDir Path root;
    private AdminProperties properties() {
        var properties = new AdminProperties(); properties.setLogStorePath(root.resolve("agent").toString());
        return properties;
    }
    private RouteControlPort control() { var port = mock(RouteControlPort.class); when(port.resourceNamespace()).thenReturn("scope-a"); return port; }

    @Test void expiredRevokedAndWrongScopeTokensCannotBeUsed() throws Exception {
        var properties = properties(); var port = control();
        Clock now = Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC);
        String token;
        try (var store = AgentStore.file(properties.getLogStorePath())) {
            var users = new AdminUserStore(properties, new BCryptPasswordEncoder());
            var security = new AgentSecurityService(properties, users, port, "admin", now);
            try {
                token = security.issue("admin", "monitor", now.millis() + 1000).token();
                assertNotNull(security.authenticate(token));
                when(port.resourceNamespace()).thenReturn("scope-b"); assertNull(security.authenticate(token));
                when(port.resourceNamespace()).thenReturn("scope-a");
            } finally { security.close(); }
            var later = new AgentSecurityService(properties, users, port, "admin", Clock.offset(now, java.time.Duration.ofSeconds(2)));
            try { assertNull(later.authenticate(token)); } finally { later.close(); users.close(); }
        }
    }

    @Test void durableWriteStopSurvivesServiceRestartAndKeepsReadPermission() throws Exception {
        var properties = properties(); var port = control();
        try (var store = AgentStore.file(properties.getLogStorePath())) {
            var users = new AdminUserStore(properties, new BCryptPasswordEncoder());
            var first = new AgentSecurityService(properties, users, port, "admin", Clock.systemUTC());
            try { first.stopWrites("admin", true); assertFalse(first.writeAllowed("admin")); }
            finally { first.close(); }
            var second = new AgentSecurityService(properties, users, port, "admin", Clock.systemUTC());
            try {
                assertTrue(second.writesStopped()); assertTrue(second.permissions("admin").contains(AgentAccess.Permission.READ));
                assertEquals("WRITE_STOP_CHANGED", store.durable().audit("scope-a", 0, 10).get(0).kind());
                second.stopWrites("admin", false); assertTrue(second.writeAllowed("admin"));
                second.beginClosing(); assertFalse(second.writeAllowed("admin"), "排空之前先关闭新写入口");
                assertTrue(second.permissions("admin").contains(AgentAccess.Permission.READ));
            } finally { second.close(); users.close(); }
        }
    }

    @Test void productionRejectsDefaultPasswordAndMissingControlConfiguration() throws Exception {
        var properties = properties();
        try (var store = AgentStore.file(properties.getLogStorePath())) {
            var users = new AdminUserStore(properties, new BCryptPasswordEncoder());
            var security = new AgentSecurityService(properties, users, control(), "admin", Clock.systemUTC());
            try {
                ReflectionTestUtils.setField(security, "productionMode", true);
                assertThrows(IllegalStateException.class, security::validateProduction);
                users.saveAccount("admin", "a-long-production-password", Set.of("ADMIN"), "scope-a", true);
                assertThrows(IllegalStateException.class, security::validateProduction);
                properties.setAdminToken("management-credential"); properties.setGatewayControlTargetId("gateway-a");
                properties.setAgentEnvironmentId("production"); assertDoesNotThrow(security::validateProduction);
            } finally { security.close(); users.close(); }
        }
    }
}
