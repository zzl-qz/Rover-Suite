package com.rover.admin.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.rover.admin.config.AdminProperties;
import com.rover.agent.core.port.AgentAccess;
import com.rover.agent.core.port.RouteControlPort;
import com.rover.agent.runtime.repository.AgentStore;
import com.rover.agent.core.model.AutoExecutionPolicy;
import com.rover.agent.core.model.ResourceRef;
import com.rover.agent.core.model.ResourceScope;
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
            var first = new AgentSecurityService(properties, users, port, "admin", Clock.systemUTC(), store);
            try {
                first.stopWrites("admin", true);
                assertFalse(first.writeAllowed("admin"));
                assertThrows(IllegalStateException.class, () -> first.stopWrites("admin", false));
            }
            finally { first.close(); }
            var second = new AgentSecurityService(properties, users, port, "admin", Clock.systemUTC(), store);
            try {
                assertTrue(second.writesStopped()); assertTrue(second.permissions("admin").contains(AgentAccess.Permission.READ));
                assertEquals("WRITE_STOP_CHANGED", store.durable().audit("scope-a", 0, 10).get(0).kind());
                assertThrows(IllegalStateException.class, () -> second.approveWriteResume("admin", 1));
                var firstApproval = second.approveWriteResume("admin", 0);
                assertTrue(firstApproval.stopped());
                assertEquals(1, firstApproval.approvals());
                users.saveAccount("admin-2", "another-long-test-password", Set.of("ADMIN"), "scope-a", true);
                var sameAdminRepeat = second.approveWriteResume("admin", 0);
                assertTrue(sameAdminRepeat.stopped(), "同一管理员重复审批不算第二个人");
                var resumed = second.approveWriteResume("admin-2", 0);
                assertFalse(resumed.stopped());
                assertEquals(2, resumed.approvals());
                assertTrue(second.writeAllowed("admin"));
                second.stopWrites("admin", true);
                var freshGeneration = second.writeResumeState();
                assertEquals(0, freshGeneration.approvals(), "新的停写必须使旧恢复审批失效");
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

    @Test void stopDisablesPreauthorizationAndResumeNeverReenablesIt() throws Exception {
        var properties = properties(); var port = control();
        try (var store = AgentStore.file(properties.getLogStorePath())) {
            var users = new AdminUserStore(properties, new BCryptPasswordEncoder());
            var security = new AgentSecurityService(properties, users, port, "admin", Clock.systemUTC(), store);
            try {
                users.saveAccount("admin-2", "another-long-test-password", Set.of("ADMIN"), "scope-a", true);
                long now = System.currentTimeMillis();
                var resource = new ResourceRef(new ResourceScope("org", "ops", "prod"), "gateway-a",
                        "GATEWAY_ROUTE", "orders", "route-uid", 1);
                var draft = AutoExecutionPolicy.create("policy-stop-test", "scope-a", resource,
                        "orders", "v2", 5, 5, now + 60_000, "admin", now);
                var enabled = draft.revise(true, draft.maxBeforeTrafficPercent(), draft.maxTrafficShiftPercent(),
                        draft.expiresAtMillis(), "admin", now + 1);
                store.autoExecutionPolicies().save(draft, 0);
                store.autoExecutionPolicies().save(enabled, draft.version());

                security.stopWrites("admin", true);
                assertFalse(store.autoExecutionPolicies().find("policy-stop-test").orElseThrow().enabled());
                assertFalse(security.automatedWriteAllowed());
                security.approveWriteResume("admin", 0);
            } finally { security.close(); users.close(); }
            var reopenedUsers = new AdminUserStore(properties, new BCryptPasswordEncoder());
            var resumed = new AgentSecurityService(properties, reopenedUsers, port, "admin", Clock.systemUTC(), store);
            try {
                assertEquals(1, resumed.writeResumeState().approvals(), "第一位管理员审批应持久化跨重启");
                resumed.approveWriteResume("admin-2", 0);
                assertFalse(resumed.writesStopped());
                assertFalse(store.autoExecutionPolicies().find("policy-stop-test").orElseThrow().enabled(),
                        "恢复写入不得自动重新启用旧预授权");
            } finally { resumed.close(); reopenedUsers.close(); }
        }
    }

    @Test void emergencyStopWaitsForAlreadyAdmittedGatewayWrite() throws Exception {
        var properties = properties(); var port = control();
        try (var store = AgentStore.file(properties.getLogStorePath())) {
            var users = new AdminUserStore(properties, new BCryptPasswordEncoder());
            var security = new AgentSecurityService(properties, users, port, "admin", Clock.systemUTC(), store);
            var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var permit = security.acquireWritePermit("admin", false);
                assertNotNull(permit);
                var started = new java.util.concurrent.CountDownLatch(1);
                var stop = pool.submit(() -> {
                    started.countDown();
                    try { Thread.sleep(100); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                    security.stopWrites("admin", true);
                });
                assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
                assertFalse(stop.isDone(), "停写应等待已获准的外部写请求退出提交窗口");
                permit.close();
                stop.get(2, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(security.writesStopped());
            } finally { pool.shutdownNow(); security.close(); users.close(); }
        }
    }

    @Test void stopWaitsForAuthorizedWriteToFinishAndThenRequiresFreshResumeApprovals() throws Exception {
        var properties = properties(); var port = control();
        try (var store = AgentStore.file(properties.getLogStorePath())) {
            var users = new AdminUserStore(properties, new BCryptPasswordEncoder());
            users.saveAccount("admin-2", "another-long-test-password", Set.of("ADMIN"), "scope-a", true);
            var security = new AgentSecurityService(properties, users, port, "admin", Clock.systemUTC(), store);
            var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var permit = security.acquireWritePermit("admin", false);
                assertNotNull(permit);
                var started = new java.util.concurrent.CountDownLatch(1);
                var stop = pool.submit(() -> {
                    started.countDown();
                    try { Thread.sleep(100); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                    security.stopWrites("admin", true);
                });
                assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
                assertFalse(stop.isDone(), "紧急停写等待已获准写请求结束 Gateway 提交窗口");
                permit.close();
                stop.get(2, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(security.writesStopped());
                assertNull(security.acquireWritePermit("admin", false), "停写后新写请求被拒绝");
                assertEquals(1, security.approveWriteResume("admin", 0).approvals());
                assertEquals(1, security.approveWriteResume("admin", 0).approvals(),
                        "同一管理员重复点击按幂等请求处理，但不能充当第二人批准");
                assertTrue(security.writesStopped());
                assertEquals(2, security.approveWriteResume("admin-2", 0).approvals());
                assertFalse(security.writesStopped(), "第二位管理员批准后才恢复");
            } finally { pool.shutdownNow(); security.close(); users.close(); }
        }
    }

    @Test void expiredResumeApprovalAndInFlightWriteKeepSystemStopped() throws Exception {
        var properties = properties(); var port = control();
        Clock start = Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneOffset.UTC);
        try (var store = AgentStore.file(properties.getLogStorePath())) {
            var users = new AdminUserStore(properties, new BCryptPasswordEncoder());
            users.saveAccount("admin-2", "another-long-test-password", Set.of("ADMIN"), "scope-a", true);
            var first = new AgentSecurityService(properties, users, port, "admin", start, store);
            try {
                first.stopWrites("admin", true);
                assertNull(first.acquireWritePermit("admin", false), "停写后新写入必须被拒绝");
                first.approveWriteResume("admin", 0);
            } finally { first.close(); users.close(); }
            var reopenedUsers = new AdminUserStore(properties, new BCryptPasswordEncoder());
            var afterExpiry = new AgentSecurityService(properties, reopenedUsers, port, "admin", Clock.offset(start, java.time.Duration.ofMinutes(16)), store);
            try {
                assertTrue(afterExpiry.writesStopped());
                assertEquals(0, afterExpiry.writeResumeState().approvals());
                var refreshedFirstApproval = afterExpiry.approveWriteResume("admin", 0);
                assertEquals(1, refreshedFirstApproval.approvals());
                assertEquals(2, afterExpiry.approveWriteResume("admin-2", 0).approvals());
                assertFalse(afterExpiry.writesStopped(), "过期旧审批不计数；新窗口内两名管理员批准后恢复");
                try (var permit = afterExpiry.acquireWritePermit("admin", false)) { assertNotNull(permit); }
            } finally { afterExpiry.close(); reopenedUsers.close(); }
        }
    }
}
