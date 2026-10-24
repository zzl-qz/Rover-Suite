package com.rover.agent.runtime.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.rover.agent.core.model.ActionEffectVerification;
import com.rover.agent.core.model.ActionStatus;
import com.rover.agent.core.model.ActionType;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.SessionStatus;
import com.rover.agent.core.model.WeightRequestUnit;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ActionEffectVerificationStoreTest {

    @Test
    void verificationSurvivesRestartUsesOptimisticVersionAndIsSelectedWhenDue() throws Exception {
        String path = Files.createTempDirectory("rover-effect-verification").resolve("rover").toString();
        ActionEffectVerification prepared = new ActionEffectVerification("a1", ActionEffectVerification.Phase.APPLY,
                1, "a".repeat(64), ActionEffectVerification.State.PREPARED, ActionEffectVerification.Outcome.PENDING,
                100, 0, 0, 1_000, 0, 0, Map.of(), Map.of(), List.of(), List.of(), 0);
        ActionEffectVerification observing;
        try (JdbcAgentStore store = new JdbcAgentStore(path)) {
            store.sessions().save(new Session("s1", "alice", "route", null, SessionStatus.ACTIVE, 1, 1, List.of()));
            store.actions().save(new com.rover.agent.core.model.AgentAction("a1", "s1", null, null,
                    ActionType.ADJUST_ROUTE_TARGET_WEIGHT, ActionStatus.SUCCESS, "r", "/", "svc", "v2",
                    10, 20, 20, WeightRequestUnit.RAW_WEIGHT, 50, 60, 1, "apply-op", 2, null,
                    "alice", "alice", 100, List.of(), "impact", null, 100, 200));
            observing = store.effectVerifications().save(prepared);
            assertEquals(1, observing.version());
            assertEquals(ActionEffectVerification.State.PREPARED, store.effectVerifications().due(299, 10).get(0).state());
            observing = store.effectVerifications().save(new ActionEffectVerification("a1",
                    ActionEffectVerification.Phase.APPLY, 1, "a".repeat(64), ActionEffectVerification.State.OBSERVING,
                    ActionEffectVerification.Outcome.PENDING, 100, 200, 300, 1_000, 0, 0, Map.of("final5xxRate", 0.1),
                    Map.of(), List.of(), List.of(), observing.version()));
            assertEquals(List.of("a1"), store.effectVerifications().due(300, 10).stream()
                    .map(ActionEffectVerification::actionId).toList());
            assertEquals(List.of(), store.effectVerifications().due(299, 10));
            ActionEffectVerification stale = observing.withVersion(1);
            assertThrows(IllegalStateException.class, () -> store.effectVerifications().save(stale));
        }
        try (JdbcAgentStore reopened = new JdbcAgentStore(path)) {
            assertEquals(observing, reopened.effectVerifications().find("a1", ActionEffectVerification.Phase.APPLY)
                    .orElseThrow());
            assertEquals(2, reopened.effectVerifications().due(300, 10).get(0).version());
        }
    }
}
