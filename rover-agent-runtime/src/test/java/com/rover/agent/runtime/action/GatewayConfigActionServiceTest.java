package com.rover.agent.runtime.action;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rover.agent.core.model.*;
import com.rover.agent.core.port.*;
import com.rover.agent.core.snapshot.ConfigEntrySnapshot;
import com.rover.agent.runtime.repository.InMemoryAgentSessionRepository;
import com.rover.agent.runtime.repository.InMemoryAgentTaskRepository;
import com.rover.agent.runtime.repository.InMemoryIncidentRepository;
import com.rover.agent.runtime.repository.InMemoryGatewayConfigActionRepository;
import com.rover.agent.runtime.repository.JdbcAgentStore;
import com.rover.agent.core.repository.GatewayConfigActionRepository;
import java.util.List;
import java.util.Set;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayConfigActionServiceTest {
    @TempDir Path tempDir;

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

    @Test void proposalAuditIdsMustBelongToSessionAndMatchEachOther() {
        ResourceScope scope = new ResourceScope("org", "ops", "dev");
        RouteControlPort control = mock(RouteControlPort.class);
        when(control.resourceScope()).thenReturn(scope);
        when(control.controlTargetId()).thenReturn("gateway-a");
        when(control.resourceNamespace()).thenReturn("target-a");
        ConfigReadPort config = () -> List.of(new ConfigEntrySnapshot("gateway", ConfigActionIntent.ALLOWED_KEY,
                "链路采样率", "0", "0", "HOT_RELOAD", true, false));
        var sessions = new InMemoryAgentSessionRepository(10);
        sessions.save(new Session("session-1", "operator", "incident", "incident-1", SessionStatus.ACTIVE,
                1, 1, List.of("incident-1"), scope, "target-a"));
        var incidents = new InMemoryIncidentRepository(10);
        incidents.save(new Incident("incident-1", "session-1", IncidentOrigin.USER, "incident",
                IncidentStatus.OPEN, ResourceTarget.route("/api/orders"), TimeRange.unspecified(), "", 1, 1, List.of("task-1")));
        var tasks = new InMemoryAgentTaskRepository(10);
        tasks.save(new TaskView("task-1", "session-1", "incident-1", TaskStatus.COMPLETED,
                AgentStepType.ROUTE_INVESTIGATION, "", ResourceTarget.route("/api/orders"), "问题",
                1, 2, List.of(), null, null, null));
        var actions = new InMemoryGatewayConfigActionRepository(10);
        var service = new GatewayConfigActionService(actions, sessions, incidents, tasks, config, control,
                AgentAccess.local(), true, () -> true);

        assertThrows(ActionRequestException.class, () -> service.propose("session-1", "foreign-incident", null, "1", "operator"));
        assertThrows(ActionRequestException.class, () -> service.propose("session-1", "incident-1", "foreign-task", "1", "operator"));
        assertThrows(ActionRequestException.class, () -> service.propose("session-1", null, "task-1", "1", "operator"));
        assertThrows(ActionRequestException.class, () -> service.propose("session-1", "other-incident", "task-1", "1", "operator"));

        GatewayConfigAction proposal = service.propose("session-1", "incident-1", "task-1", "1", "operator");
        assertEquals("incident-1", proposal.incidentId());
        assertEquals("task-1", proposal.taskId());
    }

    @Test void inMemoryRepositoryRejectsConcurrentDuplicateCompensationAtomically() throws Exception {
        var repository = new InMemoryGatewayConfigActionRepository(10);
        ResourceScope scope = new ResourceScope("org", "ops", "dev");
        ConfigActionIntent intent = new ConfigActionIntent(ConfigActionIntent.ALLOWED_KEY, "1", "0", scope, "gateway-a", "target-a");
        long now = System.currentTimeMillis();
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var writes = java.util.stream.IntStream.range(0, 2).mapToObj(i -> pool.submit(() -> {
                try {
                    start.await();
                    repository.save(new GatewayConfigAction("comp-" + i, "session-1", null, null, "operator",
                            intent, intent.intentHash(), GatewayConfigActionService.POLICY_VERSION, List.of(), "op-" + i,
                            "original-action", ConfigActionStatus.PENDING_APPROVAL, null, now, now, now + 60_000, 0));
                    return true;
                } catch (com.rover.agent.core.repository.ActionUpdateConflictException conflict) { return false; }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RuntimeException(interrupted); }
            })).toList();
            start.countDown();
            long success = 0;
            for (var write : writes) if (write.get()) success++;
            assertEquals(1, success);
            assertEquals(1, repository.bySession("session-1").size());
        } finally { pool.shutdownNow(); }
    }

    @Test void h2ConcurrentDuplicateCompensationReturnsConflictAndPersistsOnlyOne() throws Exception {
        ResourceScope scope = new ResourceScope("org", "ops", "dev");
        RouteControlPort control = mock(RouteControlPort.class);
        when(control.resourceScope()).thenReturn(scope);
        when(control.controlTargetId()).thenReturn("gateway-a");
        when(control.resourceNamespace()).thenReturn("target-a");
        ConfigReadPort config = () -> List.of(new ConfigEntrySnapshot("gateway", ConfigActionIntent.ALLOWED_KEY,
                "链路采样率", "1", "1", "HOT_RELOAD", true, false));
        long now = System.currentTimeMillis();
        ConfigActionIntent intent = new ConfigActionIntent(ConfigActionIntent.ALLOWED_KEY, "0", "1", scope, "gateway-a", "target-a");
        String dbPath = tempDir.resolve("actions").toString();
        try (JdbcAgentStore store = new JdbcAgentStore(dbPath);
                JdbcAgentStore competingStore = new JdbcAgentStore(dbPath)) {
            store.sessions().save(new Session("session-1", "operator", "incident", null, SessionStatus.ACTIVE,
                    1, 1, List.of(), scope, "target-a"));
            GatewayConfigAction original = new GatewayConfigAction("applied-action", "session-1", null, null, "operator",
                    intent, intent.intentHash(), GatewayConfigActionService.POLICY_VERSION, List.of(), "original-operation",
                    ConfigActionStatus.APPLIED, null, now, now, now + 60_000, 0);
            store.gatewayConfigActions().save(original);

            CountDownLatch bothChecked = new CountDownLatch(2);
            AtomicInteger checks = new AtomicInteger();
            GatewayConfigActionRepository racingRepository = mock(GatewayConfigActionRepository.class);
            when(racingRepository.find("applied-action")).thenAnswer(call -> store.gatewayConfigActions().find("applied-action"));
            when(racingRepository.findCompensation("applied-action")).thenAnswer(call -> {
                var found = store.gatewayConfigActions().findCompensation("applied-action");
                if (checks.incrementAndGet() <= 2) {
                    bothChecked.countDown();
                    if (!bothChecked.await(5, TimeUnit.SECONDS)) throw new AssertionError("concurrent compensation checks did not meet");
                }
                return found;
            });
            AtomicInteger writers = new AtomicInteger();
            when(racingRepository.save(any())).thenAnswer(call -> (writers.getAndIncrement() % 2 == 0
                    ? store.gatewayConfigActions() : competingStore.gatewayConfigActions()).save(call.getArgument(0)));
            var service = new GatewayConfigActionService(racingRepository, store.sessions(), store.incidents(), store.tasks(),
                    config, control, AgentAccess.local(), true, () -> true);
            var pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch start = new CountDownLatch(1);
                var attempts = java.util.stream.IntStream.range(0, 2).mapToObj(i -> pool.submit(() -> {
                    start.await();
                    try { service.proposeCompensation("applied-action", "operator"); return "created"; }
                    catch (ActionRequestException conflict) { return conflict.code().name(); }
                })).toList();
                start.countDown();
                var outcomes = attempts.stream().map(future -> {
                    try { return future.get(10, TimeUnit.SECONDS); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                }).toList();
                assertEquals(1, outcomes.stream().filter("created"::equals).count());
                assertEquals(1, outcomes.stream().filter(ActionRequestException.Code.CONFLICT.name()::equals).count());
                assertEquals(2, store.gatewayConfigActions().bySession("session-1").size());
                assertEquals("applied-action", store.gatewayConfigActions().findCompensation("applied-action").orElseThrow().compensatesActionId());
                assertThrows(IllegalStateException.class, () -> competingStore.gatewayConfigActions().save(
                        new GatewayConfigAction("direct-duplicate", "session-1", null, null, "operator", intent,
                                intent.intentHash(), GatewayConfigActionService.POLICY_VERSION, List.of(),
                                "direct-duplicate-operation", "applied-action", ConfigActionStatus.PENDING_APPROVAL,
                                null, now, now, now + 60_000, 0)));
            } finally { pool.shutdownNow(); }
        }
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
