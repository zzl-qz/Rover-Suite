package com.rover.agent.runtime.action;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rover.agent.core.model.*;
import com.rover.agent.core.port.*;
import com.rover.agent.runtime.repository.InMemoryGatewayConfigActionRepository;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class GatewayConfigActionCommandRunnerTest {
    private static final ResourceScope SCOPE = new ResourceScope("org", "team", "test");

    @Test void restartRecoveryQueriesOnlyOriginalOperationAndNeverReplaysWrite() throws Exception {
        var repo = new InMemoryGatewayConfigActionRepository(10);
        var action = approved(ConfigActionStatus.EXECUTING, System.currentTimeMillis() + 60_000);
        repo.save(action);
        var gateway = mock(GatewayConfigActionPort.class);
        when(gateway.query(eq(action.operationId()), anyString(), anyString(), anyString()))
                .thenReturn(new GatewayConfigActionReceipt(action.operationId(), ConfigActionIntent.ALLOWED_KEY,
                        "0.0", "1", GatewayConfigActionReceipt.Status.APPLIED, false, "回执已核验"));
        var runner = runner(repo, gateway, access(), () -> true);
        try {
            runner.poll();
            await(repo, action.actionId(), ConfigActionStatus.APPLIED);
            verify(gateway).query(action.operationId(), ConfigActionIntent.ALLOWED_KEY, "0.0", "1");
            verify(gateway, never()).execute(anyString(), anyString(), anyString(), anyString());
        } finally { runner.close(); }
    }

    @Test void mismatchedOrIncompleteUnknownReceiptRemainsUnknown() throws Exception {
        var repo = new InMemoryGatewayConfigActionRepository(10);
        var action = approved(ConfigActionStatus.EXECUTING, System.currentTimeMillis() + 60_000);
        repo.save(action);
        var gateway = mock(GatewayConfigActionPort.class);
        when(gateway.query(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new GatewayConfigActionReceipt(action.operationId(), null, null, null,
                        GatewayConfigActionReceipt.Status.UNKNOWN, false, "无记录"));
        var runner = runner(repo, gateway, access(), () -> true);
        try {
            runner.poll();
            await(repo, action.actionId(), ConfigActionStatus.UNKNOWN);
            verify(gateway, never()).execute(anyString(), anyString(), anyString(), anyString());
        } finally { runner.close(); }
    }

    @Test void incompleteAppliedReceiptCannotBeTreatedAsSuccess() throws Exception {
        var repo = new InMemoryGatewayConfigActionRepository(10);
        var action = approved(ConfigActionStatus.EXECUTING, System.currentTimeMillis() + 60_000);
        repo.save(action);
        var gateway = mock(GatewayConfigActionPort.class);
        when(gateway.query(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new GatewayConfigActionReceipt(action.operationId(), null, null, null,
                        GatewayConfigActionReceipt.Status.APPLIED, false, "malformed receipt"));
        var runner = runner(repo, gateway, access(), () -> true);
        try {
            runner.poll();
            await(repo, action.actionId(), ConfigActionStatus.UNKNOWN);
            verify(gateway, never()).execute(anyString(), anyString(), anyString(), anyString());
        } finally { runner.close(); }
    }

    @Test void expiredApprovedQueueDoesNotCallGateway() throws Exception {
        var repo = new InMemoryGatewayConfigActionRepository(10);
        var action = approved(ConfigActionStatus.QUEUED, System.currentTimeMillis() - 1);
        repo.save(action);
        var gateway = mock(GatewayConfigActionPort.class);
        var runner = runner(repo, gateway, access(), () -> true);
        try {
            // The action fixture must have expiry strictly after its creation timestamp.
            // Give its minimum +1ms expiry time to pass before exercising the queued expiry path.
            Thread.sleep(10);
            runner.poll();
            await(repo, action.actionId(), ConfigActionStatus.EXPIRED);
            verifyNoInteractions(gateway);
        } finally { runner.close(); }
    }

    @Test void revokedApproverStopsBeforeGatewayWrite() throws Exception {
        var repo = new InMemoryGatewayConfigActionRepository(10);
        var action = approved(ConfigActionStatus.QUEUED, System.currentTimeMillis() + 60_000);
        repo.save(action);
        var gateway = mock(GatewayConfigActionPort.class);
        AgentAccess access = new AgentAccess() {
            public Set<Permission> permissions(String actor) {
                return "second".equals(actor) ? Set.of(Permission.APPROVE) : Set.of();
            }
            public WritePermit acquireWritePermit(String actor, boolean automated) { return null; }
        };
        var runner = runner(repo, gateway, access, () -> true);
        try {
            runner.poll();
            await(repo, action.actionId(), ConfigActionStatus.FAILED);
            verifyNoInteractions(gateway);
        } finally { runner.close(); }
    }

    @Test void targetDriftStopsQueuedWriteBeforeGatewayCall() throws Exception {
        var repo = new InMemoryGatewayConfigActionRepository(10);
        var action = approved(ConfigActionStatus.QUEUED, System.currentTimeMillis() + 60_000);
        repo.save(action);
        var gateway = mock(GatewayConfigActionPort.class);
        // Simulate target reconfiguration between queue admission and the actual call.
        RouteControlPort changed = target("gateway-replacement", "namespace-replacement");
        var driftingRunner = new GatewayConfigActionCommandRunner(repo, gateway, access(), changed, () -> true);
        try {
            // Persisted intent remains bound to the old target; the current management port points elsewhere.
            driftingRunner.poll();
            await(repo, action.actionId(), ConfigActionStatus.FAILED);
            verifyNoInteractions(gateway);
            assertTrue(repo.find(action.actionId()).orElseThrow().errorMessage().contains("目标已变化"));
        } finally { driftingRunner.close(); }
    }

    @Test void targetDriftKeepsRecoveryUnknownWithoutQueryingReplacementGateway() throws Exception {
        var repo = new InMemoryGatewayConfigActionRepository(10);
        var action = approved(ConfigActionStatus.EXECUTING, System.currentTimeMillis() + 60_000);
        repo.save(action);
        var gateway = mock(GatewayConfigActionPort.class);
        var runner = new GatewayConfigActionCommandRunner(repo, gateway, access(), target("gateway-replacement", "namespace-replacement"), () -> true);
        try {
            runner.poll();
            await(repo, action.actionId(), ConfigActionStatus.UNKNOWN);
            verifyNoInteractions(gateway);
            assertTrue(repo.find(action.actionId()).orElseThrow().errorMessage().contains("未查询新目标"));
        } finally { runner.close(); }
    }

    private static GatewayConfigActionCommandRunner runner(InMemoryGatewayConfigActionRepository repo,
            GatewayConfigActionPort gateway, AgentAccess access, java.util.function.BooleanSupplier durable) {
        return new GatewayConfigActionCommandRunner(repo, gateway, access, target("gateway-test", "namespace-test"), durable);
    }
    private static RouteControlPort target(String id, String namespace) {
        return new RouteControlPort() {
            public ResourceScope resourceScope() { return SCOPE; }
            public String controlTargetId() { return id; }
            public String resourceNamespace() { return namespace; }
            public RouteControlState state() { return new RouteControlState(0, "", List.of()); }
            public RouteChangePreview preview(String routeId, String service, String group, int weight) {
                return new RouteChangePreview(0, "", List.of());
            }
            public RouteChangeResult adjustTargetWeight(String routeId, String service, String group, int weight,
                    int revision, String operationId) { throw new UnsupportedOperationException(); }
            public RouteOperation operation(String operationId) { throw new UnsupportedOperationException(); }
        };
    }
    private static AgentAccess access() {
        return new AgentAccess() {
            public Set<Permission> permissions(String actor) { return Set.of(Permission.APPROVE); }
            public WritePermit acquireWritePermit(String actor, boolean automated) { return () -> { }; }
        };
    }
    private static GatewayConfigAction approved(ConfigActionStatus status, long expires) {
        long now=System.currentTimeMillis();
        ConfigActionIntent intent = new ConfigActionIntent(ConfigActionIntent.ALLOWED_KEY, "0.0", "1", SCOPE,
                "gateway-test", "namespace-test");
        List<ConfigActionApproval> approvals = List.of(
                new ConfigActionApproval("first", intent.intentHash(), GatewayConfigActionService.POLICY_VERSION, now),
                new ConfigActionApproval("second", intent.intentHash(), GatewayConfigActionService.POLICY_VERSION, now));
        return new GatewayConfigAction("cfg-action-runner", "session-1", null, null, "operator", intent,
                intent.intentHash(), GatewayConfigActionService.POLICY_VERSION, approvals, "operation-runner",
                status, null, now, now, Math.max(now + 1, expires), 0);
    }
    private static void await(InMemoryGatewayConfigActionRepository repo, String id, ConfigActionStatus expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (repo.find(id).orElseThrow().status() == expected) return;
            Thread.sleep(10);
        }
        assertEquals(expected, repo.find(id).orElseThrow().status());
    }
}
