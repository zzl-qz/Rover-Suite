package com.rover.agent.runtime.action;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rover.agent.core.model.*;
import com.rover.agent.core.port.*;
import com.rover.agent.core.snapshot.ConfigEntrySnapshot;
import com.rover.agent.runtime.repository.InMemoryAgentSessionRepository;
import com.rover.agent.runtime.repository.InMemoryGatewayConfigActionRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GatewayConfigActionServiceTest {
    @Test void compensationIsASeparateTwoPersonIntentAndRequiresUnchangedAppliedValue() {
        ResourceScope scope = new ResourceScope("org", "ops", "dev");
        RouteControlPort control = mock(RouteControlPort.class);
        when(control.resourceScope()).thenReturn(scope);
        when(control.controlTargetId()).thenReturn("gateway-a");
        when(control.resourceNamespace()).thenReturn("target-a");
        ConfigReadPort config = mock(ConfigReadPort.class);
        when(config.configs()).thenReturn(List.of(new ConfigEntrySnapshot("gateway", ConfigActionIntent.ALLOWED_KEY,
                "链路采样率", "1.0", "1", "HOT_RELOAD", true, false)));
        var sessions = new InMemoryAgentSessionRepository(10);
        sessions.save(new Session("session-1", "operator", "incident", null, SessionStatus.ACTIVE,
                1, 1, List.of(), scope, "target-a"));
        AgentAccess access = new AgentAccess() {
            public Set<Permission> permissions(String actor) { return Set.of(Permission.PROPOSE, Permission.APPROVE, Permission.READ); }
            public boolean writeAllowed(String actor) { return true; }
            public WritePermit acquireWritePermit(String actor, boolean automated) { return () -> { }; }
        };
        var repository = new InMemoryGatewayConfigActionRepository(10);
        var service = new GatewayConfigActionService(repository, sessions, config, control, access, true, () -> true);
        long now = System.currentTimeMillis();
        ConfigActionIntent intent = new ConfigActionIntent(ConfigActionIntent.ALLOWED_KEY, "0.0", "1", scope, "gateway-a", "target-a");
        GatewayConfigAction applied = new GatewayConfigAction("applied-action", "session-1", null, null, "operator",
                intent, intent.intentHash(), GatewayConfigActionService.POLICY_VERSION, List.of(), "original-operation",
                ConfigActionStatus.APPLIED, null, now, now, now + 60_000, 0);
        repository.save(applied);

        GatewayConfigAction compensation = service.proposeCompensation(applied.actionId(), "operator");

        assertEquals(applied.actionId(), compensation.compensatesActionId());
        assertEquals("1.0", compensation.intent().beforeValue());
        assertEquals("0", compensation.intent().desiredValue());
        assertEquals(ConfigActionStatus.PENDING_APPROVAL, compensation.status());
        assertEquals("original-operation", repository.find(applied.actionId()).orElseThrow().operationId());
        assertEquals(ConfigActionStatus.APPLIED, repository.find(applied.actionId()).orElseThrow().status());
        assertThrows(ActionRequestException.class, () -> service.proposeCompensation(applied.actionId(), "operator"));
    }

    @Test void compensationRefusesToOverwriteChangedGatewayValue() {
        ResourceScope scope = new ResourceScope("org", "ops", "dev");
        RouteControlPort control = mock(RouteControlPort.class);
        when(control.resourceScope()).thenReturn(scope);
        when(control.controlTargetId()).thenReturn("gateway-a");
        when(control.resourceNamespace()).thenReturn("target-a");
        ConfigReadPort config = () -> List.of(new ConfigEntrySnapshot("gateway", ConfigActionIntent.ALLOWED_KEY,
                "链路采样率", "0", "0", "HOT_RELOAD", true, false));
        var sessions = new InMemoryAgentSessionRepository(10);
        sessions.save(new Session("session-1", "operator", "incident", null, SessionStatus.ACTIVE,
                1, 1, List.of(), scope, "target-a"));
        var repository = new InMemoryGatewayConfigActionRepository(10);
        var service = new GatewayConfigActionService(repository, sessions, config, control, AgentAccess.local(), true, () -> true);
        long now = System.currentTimeMillis();
        ConfigActionIntent intent = new ConfigActionIntent(ConfigActionIntent.ALLOWED_KEY, "0.0", "1", scope, "gateway-a", "target-a");
        repository.save(new GatewayConfigAction("applied-action", "session-1", null, null, "operator",
                intent, intent.intentHash(), GatewayConfigActionService.POLICY_VERSION, List.of(), "original-operation",
                ConfigActionStatus.APPLIED, null, now, now, now + 60_000, 0));
        assertThrows(ActionRequestException.class, () -> service.proposeCompensation("applied-action", "operator"));
        assertTrue(repository.bySession("session-1").stream().noneMatch(a -> a.compensatesActionId() != null));
    }

    @Test void twoDifferentApproversAreRequiredAndPlanIsBoundToDefaultZeroFormat() {
        ResourceScope scope = new ResourceScope("org", "ops", "dev");
        RouteControlPort control = mock(RouteControlPort.class);
        when(control.resourceScope()).thenReturn(scope);
        when(control.controlTargetId()).thenReturn("gateway-a");
        when(control.resourceNamespace()).thenReturn("target-a");
        ConfigReadPort config = mock(ConfigReadPort.class);
        when(config.configs()).thenReturn(List.of(new ConfigEntrySnapshot("gateway", ConfigActionIntent.ALLOWED_KEY,
                "链路采样率", "0.0", "0", "HOT_RELOAD", true, false)));
        var sessions = new InMemoryAgentSessionRepository(10);
        sessions.save(new Session("session-1", "proposer", "incident", null, SessionStatus.ACTIVE,
                1, 1, List.of(), scope, "target-a"));
        AgentAccess access = new AgentAccess() {
            public Set<Permission> permissions(String actor) { return Set.of(Permission.PROPOSE, Permission.APPROVE, Permission.READ); }
            public boolean writeAllowed(String actor) { return true; }
            public WritePermit acquireWritePermit(String actor, boolean automated) { return () -> { }; }
        };
        var repository = new InMemoryGatewayConfigActionRepository(10);
        var service = new GatewayConfigActionService(repository, sessions, config, control, access, true, () -> true);

        GatewayConfigAction draft = service.propose("session-1", null, null, "1", "proposer");
        assertEquals("0.0", draft.intent().beforeValue());
        ActionRequestException unsupported = assertThrows(ActionRequestException.class,
                () -> service.propose("session-1", null, null, "0.5", "proposer"));
        assertEquals(ActionRequestException.Code.INVALID_REQUEST, unsupported.code());
        ActionRequestException unchanged = assertThrows(ActionRequestException.class,
                () -> service.propose("session-1", null, null, "0", "proposer"));
        assertEquals(ActionRequestException.Code.INVALID_REQUEST, unchanged.code());
        assertThrows(ActionRequestException.class, () -> service.approve(draft.actionId(), draft.intentHash(), "proposer"));
        GatewayConfigAction first = service.approve(draft.actionId(), draft.intentHash(), "approver-a");
        assertEquals(ConfigActionStatus.PENDING_APPROVAL, first.status());
        GatewayConfigAction queued = service.approve(draft.actionId(), draft.intentHash(), "approver-b");
        assertEquals(ConfigActionStatus.QUEUED, queued.status());
        assertTrue(queued.approvalComplete());
    }
}
