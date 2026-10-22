package com.rover.agent.runtime.repository;

import static org.junit.jupiter.api.Assertions.*;

import com.rover.agent.core.model.ActionStatus;
import com.rover.agent.core.model.ActionType;
import com.rover.agent.core.model.AgentAction;
import com.rover.agent.core.model.RemediationPlan;
import com.rover.agent.core.model.ResourceRef;
import com.rover.agent.core.model.ResourceScope;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.SessionStatus;
import com.rover.agent.core.model.WeightRequestUnit;
import com.rover.agent.core.repository.ActionUpdateConflictException;
import com.rover.agent.core.repository.AgentActionRepository;
import java.nio.file.Files;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RemediationPlanStoreTest {
    private static final ResourceScope SCOPE = new ResourceScope("org", "ops", "test");
    private static final ResourceRef RESOURCE = new ResourceRef(SCOPE, "gateway", "ROUTE", "order-api", "uid-1", 1);

    @Test
    void restartKeepsEveryImmutableVersionAndSupersedesOnlyThePendingParent() throws Exception {
        String path = path();
        AgentAction first;
        AgentAction second;
        try (JdbcAgentStore store = store(path)) {
            first = store.actions().save(planned("a1", "p1", 1, 20));
            second = store.actions().save(planned("a2", "p1", 2, 40));
            assertEquals(17, store.schemaVersion());
            assertEquals(2, store.actions().find("a1").orElseThrow().version());
            assertTrue(store.audit("", 0, 100).stream().anyMatch(a -> a.kind().equals("ACTION_SUPERSEDED")));
        }
        try (JdbcAgentStore reopened = new JdbcAgentStore(path)) {
            AgentAction old = reopened.actions().find("a1").orElseThrow();
            assertEquals(ActionStatus.SUPERSEDED, old.status());
            assertEquals(first.plan(), old.plan());
            assertEquals(first.desiredWeight(), old.desiredWeight());
            assertEquals(first.applyOperationId(), old.applyOperationId());
            assertEquals(second, reopened.actions().find("a2").orElseThrow());
            assertEquals(List.of(first.plan(), second.plan()), reopened.actions().planVersions("p1"));
            assertTrue(reopened.actions().transition("a1", ActionStatus.PENDING_APPROVAL,
                    a -> move(a, ActionStatus.EXECUTING)).isEmpty());
            assertThrows(ActionUpdateConflictException.class,
                    () -> reopened.actions().save(move(old, ActionStatus.EXECUTING)));
        }
    }

    @Test
    void bothStoresRejectIntentMutationDuplicateVersionsSkippedVersionsAndCrossScopeChanges() throws Exception {
        try (JdbcAgentStore store = store(path())) {
            for (AgentActionRepository repo : List.of(new InMemoryAgentActionRepository(20), store.actions())) {
                AgentAction first = repo.save(planned("a1", "p1", 1, 20));
                AgentAction changed = base("a1", 30).withVersion(first.version());
                assertThrows(IllegalArgumentException.class, () -> repo.save(changed));
                AgentAction changedPlan = first.withPlan(RemediationPlan.forRouteWeight(first, "other", 1, 10_000L));
                assertThrows(IllegalArgumentException.class, () -> repo.save(changedPlan));
                assertThrows(ActionUpdateConflictException.class, () -> repo.save(planned("duplicate", "p1", 1, 20)));
                assertThrows(ActionUpdateConflictException.class, () -> repo.save(planned("skip", "p1", 3, 30)));
                AgentAction otherScope = base("cross", 30).withResourceRef(new ResourceRef(
                        new ResourceScope("org", "ops", "prod"), "gateway", "ROUTE", "order-api", "uid-1", 1));
                otherScope = otherScope.withPlan(RemediationPlan.forRouteWeight(otherScope, "p1", 2, 10_000L));
                AgentAction crossScope = otherScope;
                assertThrows(ActionUpdateConflictException.class, () -> repo.save(crossScope));
                assertEquals(first, repo.find("a1").orElseThrow());
                assertEquals(List.of(first.plan()), repo.planVersions("p1"));
                assertTrue(repo.find("duplicate").isEmpty());
            }
        }
    }

    @Test
    void approvalAndRevisionRaceHasOnlyOneWinnerInBothStores() throws Exception {
        try (JdbcAgentStore store = store(path())) {
            for (AgentActionRepository repo : List.of(new InMemoryAgentActionRepository(10), store.actions())) {
                assertOnlyOneWinner(repo, repo);
            }
        }
    }

    @Test
    void separateConnectionsCannotApproveAndReviseTheSameVersion() throws Exception {
        String path = path();
        try (JdbcAgentStore first = store(path); JdbcAgentStore second = new JdbcAgentStore(path)) {
            assertOnlyOneWinner(first.actions(), second.actions());
        }
    }

    private static void assertOnlyOneWinner(AgentActionRepository approveRepo, AgentActionRepository reviseRepo) throws Exception {
        approveRepo.save(planned("race-1", "race", 1, 20));
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var approved = pool.submit(() -> {
                start.await();
                try {
                    return approveRepo.transition("race-1", ActionStatus.PENDING_APPROVAL,
                            a -> move(a, ActionStatus.EXECUTING)).isPresent();
                } catch (ActionUpdateConflictException ex) { return false; }
            });
            var revised = pool.submit(() -> {
                start.await();
                try { reviseRepo.save(planned("race-2", "race", 2, 30)); return true; }
                catch (ActionUpdateConflictException ex) { return false; }
            });
            start.countDown();
            boolean approvedWon = approved.get(10, TimeUnit.SECONDS);
            boolean revisedWon = revised.get(10, TimeUnit.SECONDS);
            assertNotEquals(approvedWon, revisedWon, "审批和修订不能同时获得执行权，也不能都丢失");
            assertEquals(revisedWon ? ActionStatus.SUPERSEDED : ActionStatus.EXECUTING,
                    approveRepo.find("race-1").orElseThrow().status());
            assertEquals(revisedWon ? 2 : 1, approveRepo.planVersions("race").size());
        } finally { pool.shutdownNow(); }
    }

    @Test
    void constraintFailureRollsBackNewActionPlanParentStateAndAudit() throws Exception {
        String path = path();
        try (JdbcAgentStore store = store(path)) {
            AgentAction first = store.actions().save(planned("a1", "p1", 1, 20));
            int auditCount = store.audit("", 0, 100).size();
            try (var connection = DriverManager.getConnection("jdbc:h2:file:" + path + ";AUTO_SERVER=TRUE", "sa", "");
                 var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE agent_remediation_plan ADD CONSTRAINT reject_revision CHECK(plan_version < 2)");
            }
            assertThrows(IllegalStateException.class, () -> store.actions().save(planned("a2", "p1", 2, 40)));
            assertEquals(first, store.actions().find("a1").orElseThrow());
            assertTrue(store.actions().find("a2").isEmpty());
            assertEquals(List.of(first.plan()), store.actions().planVersions("p1"));
            assertEquals(auditCount, store.audit("", 0, 100).size());
        }
    }

    @Test
    void sessionCleanupRetainsPlansWithoutResurrectingExecutionPermission() throws Exception {
        String path = path();
        RemediationPlan plan;
        try (JdbcAgentStore store = store(path)) {
            plan = store.actions().save(planned("a1", "p1", 1, 20)).plan();
            store.sessions().remove("s1");
            assertTrue(store.actions().find("a1").isEmpty());
            assertEquals(List.of(plan), store.actions().planVersions("p1"));
            store.sessions().save(session());
            assertThrows(ActionUpdateConflictException.class, () -> store.actions().save(planned("a2", "p1", 2, 30)));
            assertThrows(ActionUpdateConflictException.class, () -> store.actions().save(base("a1", 20)));
        }
        try (JdbcAgentStore reopened = new JdbcAgentStore(path)) {
            assertEquals(plan, reopened.actions().findPlan("p1", 1).orElseThrow());
            assertTrue(reopened.actions().bySession("s1").isEmpty());
        }
    }

    @Test
    void memoryCapacityFailureDoesNotOrphanThePlanOrSupersedeItsParent() {
        InMemoryAgentActionRepository repo = new InMemoryAgentActionRepository(1);
        AgentAction first = repo.save(planned("a1", "p1", 1, 20));
        assertThrows(StoreCapacityExceededException.class, () -> repo.save(planned("a2", "p1", 2, 30)));
        assertEquals(first, repo.find("a1").orElseThrow());
        assertEquals(List.of(first.plan()), repo.planVersions("p1"));
        repo.removeBySession("s1");
        assertEquals(List.of(first.plan()), repo.planVersions("p1"));
        assertThrows(ActionUpdateConflictException.class, () -> repo.save(base("a1", 20)));
        assertThrows(ActionUpdateConflictException.class, () -> repo.save(planned("a2", "p1", 2, 30)));
        assertThrows(StoreCapacityExceededException.class, () -> repo.save(planned("a3", "p2", 1, 30)));
        assertTrue(repo.planVersions("p2").isEmpty());
    }

    @Test
    void oldRowsMigrateWithoutInventingPlansAndRetainReconciliationState() throws Exception {
        String path = path();
        try (JdbcAgentStore store = store(path)) { store.actions().save(base("legacy", 20)); }
        try (var connection = DriverManager.getConnection("jdbc:h2:file:" + path + ";AUTO_SERVER=TRUE", "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("DROP TABLE agent_action_approval");
            statement.execute("DROP TABLE agent_remediation_plan");
            statement.execute("DELETE FROM agent_schema_migrations WHERE version>=11");
        }
        try (JdbcAgentStore store = new JdbcAgentStore(path)) {
            AgentAction legacy = store.actions().find("legacy").orElseThrow();
            assertNull(legacy.plan());
            assertEquals(17, store.schemaVersion());
            assertTrue(store.actions().planVersions("legacy").isEmpty());
            assertNull(store.actions().transition("legacy", ActionStatus.PENDING_APPROVAL,
                    a -> move(a, ActionStatus.REJECTED)).orElseThrow().plan());
        }
    }

    @Test
    void rejectedPlanCannotBeRevised() throws Exception {
        try (JdbcAgentStore store = store(path())) {
            for (AgentActionRepository repo : List.of(new InMemoryAgentActionRepository(10), store.actions())) {
                repo.save(planned("a1", "p1", 1, 20));
                repo.transition("a1", ActionStatus.PENDING_APPROVAL, a -> move(a, ActionStatus.REJECTED));
                assertThrows(ActionUpdateConflictException.class, () -> repo.save(planned("a2", "p1", 2, 40)));
                assertEquals(1, repo.planVersions("p1").size());
            }
        }
    }

    private static String path() throws Exception { return Files.createTempDirectory("rover-plans").resolve("agent").toString(); }

    private static JdbcAgentStore store(String path) {
        JdbcAgentStore store = new JdbcAgentStore(path);
        store.sessions().save(session());
        return store;
    }

    private static Session session() { return new Session("s1", "alice", "订单处置", null, SessionStatus.ACTIVE, 1, 1, List.of()); }

    private static AgentAction planned(String actionId, String planId, int version, int desiredWeight) {
        AgentAction action = base(actionId, desiredWeight);
        return action.withPlan(RemediationPlan.forRouteWeight(action, planId, version, 10_000L));
    }

    private static AgentAction base(String id, int desiredWeight) {
        return new AgentAction(id, "s1", null, null, ActionType.ADJUST_ROUTE_TARGET_WEIGHT,
                ActionStatus.PENDING_APPROVAL, "order-api", "/api/order", "order-service", "v2", 5,
                desiredWeight, desiredWeight, WeightRequestUnit.RAW_WEIGHT, 5.0, -1.0, 17,
                null, null, null, "alice", null, 0, List.of("灰度权重变更"), "调整灰度流量", null,
                100L, 100L).withResourceRef(RESOURCE);
    }

    private static AgentAction move(AgentAction action, ActionStatus status) {
        return new AgentAction(action.actionId(), action.sessionId(), action.incidentId(), action.taskId(),
                action.type(), status, action.routeId(), action.businessPrefix(), action.serviceName(), action.group(),
                action.beforeWeight(), action.desiredWeight(), action.requestedValue(), action.requestedUnit(),
                action.beforeTrafficPercent(), action.desiredTrafficPercent(), action.expectedRevision(),
                action.applyOperationId(), action.appliedRevision(), action.rollbackOperationId(), action.requestedBy(),
                action.approvedBy(), action.approvedAtMillis(), action.preview(), action.impact(), action.errorMessage(),
                action.createdAtMillis(), 200L, action.version(), action.resourceRef(), action.plan());
    }
}
