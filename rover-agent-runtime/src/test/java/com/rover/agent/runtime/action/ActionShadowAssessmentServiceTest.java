package com.rover.agent.runtime.action;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rover.agent.core.model.*;
import com.rover.agent.core.port.*;
import com.rover.agent.runtime.repository.InMemoryActionCertificationRepository;
import com.rover.agent.runtime.repository.InMemoryAgentActionRepository;
import com.rover.agent.runtime.repository.InMemoryAgentSessionRepository;
import com.rover.agent.runtime.repository.InMemoryActionCommandRepository;
import com.rover.agent.runtime.repository.InMemoryAutonomousActionBudgetRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ActionShadowAssessmentServiceTest {
    @Test void shadowOnlyReadsAndPreviewsAndRecordsPassWithoutWriting() {
        var scope = new ResourceScope("org", "team", "staging");
        var resource = new ResourceRef(scope, "gateway", "GATEWAY_ROUTE", "orders", "uid-1", 1);
        RouteControlPort control = mock(RouteControlPort.class);
        when(control.state()).thenReturn(new RouteControlState(7, "", List.of(new RouteControlRoute(
                "orders", "/orders", "", List.of(new RouteControlTarget("order-service", "v2", 20),
                new RouteControlTarget("order-service", "v1", 80)), resource))));
        when(control.shadowPreview("orders", "order-service", "v2", 0))
                .thenReturn(new RouteChangePreview(7, "ok", List.of("v2:20->0")));
        var repository = new InMemoryAgentActionRepository(10);
        var action = action(resource);
        repository.save(action);
        var certifications = new InMemoryActionCertificationRepository();
        var service = new ActionShadowAssessmentService(repository, control, certifications);
        var run = service.assess(action.actionId(), "admin");
        assertEquals(ActionShadowRun.Outcome.PASS, run.outcome());
        assertEquals(action.plan().planHash(), run.planHash());
        assertEquals(64, run.previewHash().length());
        assertEquals("admin", run.assessedBy());
        verify(control).shadowPreview("orders", "order-service", "v2", 0);
        verify(control, never()).adjustTargetWeight(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyString());
    }

    @Test void resourceUidAndGenerationMustMatchBeforePreview() {
        var scope = new ResourceScope("org", "team", "staging");
        var expected = new ResourceRef(scope, "gateway", "GATEWAY_ROUTE", "orders", "uid-1", 1);
        var recreated = new ResourceRef(scope, "gateway", "GATEWAY_ROUTE", "orders", "uid-2", 2);
        RouteControlPort control = mock(RouteControlPort.class);
        when(control.state()).thenReturn(new RouteControlState(7, "", List.of(new RouteControlRoute(
                "orders", "/orders", "", List.of(new RouteControlTarget("order-service", "v2", 20),
                new RouteControlTarget("order-service", "v1", 80)), recreated))));
        var repository = new InMemoryAgentActionRepository(10);
        var action = action(expected); repository.save(action);
        var service = new ActionShadowAssessmentService(repository, control, new InMemoryActionCertificationRepository());
        var run = service.assess(action.actionId(), "admin");
        assertEquals(ActionShadowRun.Outcome.FAIL, run.outcome());
        assertTrue(run.detail().contains("UID"));
        verify(control, never()).shadowPreview(anyString(), anyString(), anyString(), anyInt());
    }

    @Test void changedRevisionFailsShadowAndUnavailablePersistenceBlocksRun() {
        var scope = new ResourceScope("org", "team", "staging");
        var resource = new ResourceRef(scope, "gateway", "GATEWAY_ROUTE", "orders", "uid-1", 1);
        RouteControlPort control = mock(RouteControlPort.class);
        when(control.state()).thenReturn(new RouteControlState(8, "", List.of()));
        var repository = new InMemoryAgentActionRepository(10);
        var action = action(resource); repository.save(action);
        var certs = new InMemoryActionCertificationRepository();
        var service = new ActionShadowAssessmentService(repository, control, certs);
        assertEquals(ActionShadowRun.Outcome.FAIL, service.assess(action.actionId(), "admin").outcome());
        var unavailable = new ActionShadowAssessmentService(repository, control, certs, () -> false);
        assertFalse(unavailable.ready());
        assertThrows(IllegalStateException.class, () -> unavailable.assess(action.actionId(), "admin"));
    }

    @Test void newerFailedShadowRunInvalidatesExistingCertification() {
        var scope = new ResourceScope("org", "team", "staging");
        var subject = new ActionCertificationSubject(scope, ActionType.ADJUST_ROUTE_TARGET_WEIGHT, "runbook",
                "1", "adapter-1", "effect-1", "query-1", "gateway");
        var repository = new InMemoryActionCertificationRepository();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            repository.saveShadow(new ActionShadowRun("pass-" + i, subject, "action-", i + 1,
                    "a".repeat(64), ActionShadowRun.Outcome.PASS, "ok", 7,
                    "b".repeat(64), now + i));
        }
        var service = new ActionShadowAssessmentService(new InMemoryAgentActionRepository(2), mock(RouteControlPort.class), repository);
        String sample;
        try { sample = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest("pass-0|pass-1|pass-2".getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        var cert = new ActionCertification(subject, 1, ActionCertification.State.ACTIVE, 3, sample,
                "admin", now, now + 60_000, "admin", now + 3);
        repository.save(cert, 0);
        var sessions = new InMemoryAgentSessionRepository(2);
        AgentAccess access = principal -> Set.of(AgentAccess.Permission.values());
        var actions = new InMemoryAgentActionRepository(2);
        var businessEffect = new com.rover.agent.runtime.verification.BusinessEffectPolicy(true,
                60_000, 5_000, 30_000, 2, 10, 0.9, 120_000, 0.01, 2_000);
        var managedControl = mock(RouteControlPort.class);
        when(managedControl.resourceScope()).thenReturn(scope);
        when(managedControl.controlTargetId()).thenReturn("gateway");
        var managed = new AgentActionService(actions, sessions, managedControl,
                new ActionExecutorRegistry(List.of()), access, RiskBasedApprovalPolicy.defaults(),
                new InMemoryActionCommandRepository(10), true, null, businessEffect,
                new InMemoryAutonomousActionBudgetRepository(), repository, service);
        assertEquals(cert, managed.currentCertification(subject, "admin"));
        repository.saveShadow(new ActionShadowRun("fail-new", subject, "action-new", 1, "d".repeat(64),
                ActionShadowRun.Outcome.FAIL, "revision changed", 8, "", now + 10));
        var latest = repository.recentShadowRuns(subject.fingerprint(), 100);
        assertEquals(ActionShadowRun.Outcome.FAIL, latest.get(0).outcome());
        assertThrows(ActionRequestException.class, () -> managed.currentCertification(subject, "admin"),
                "新失败必须让认证服务失败关闭，而不是只标注最近结果");
    }

    @Test void certificationRequiresThreePassingRunsAcrossPlanVersionsAndUsesVersionCas() {
        var scope = new ResourceScope("org", "team", "staging");
        var subject = new ActionCertificationSubject(scope, ActionType.ADJUST_ROUTE_TARGET_WEIGHT,
                "runbook", "1", "adapter-1", "effect-1", "query-1", "gateway");
        var records = new InMemoryActionCertificationRepository();
        long now = System.currentTimeMillis();
        records.saveShadow(new ActionShadowRun("one", subject, "a1", 1, "1".repeat(64),
                ActionShadowRun.Outcome.PASS, "ok", 1, "2".repeat(64), now, "admin"));
        records.saveShadow(new ActionShadowRun("two", subject, "a2", 1, "1".repeat(64),
                ActionShadowRun.Outcome.PASS, "ok", 1, "2".repeat(64), now + 1, "admin"));
        records.saveShadow(new ActionShadowRun("three", subject, "a3", 1, "1".repeat(64),
                ActionShadowRun.Outcome.PASS, "ok", 1, "2".repeat(64), now + 2, "admin"));
        var sessions = new InMemoryAgentSessionRepository(2);
        AgentAccess access = principal -> Set.of(AgentAccess.Permission.values());
        var control = mock(RouteControlPort.class);
        when(control.resourceScope()).thenReturn(scope);
        when(control.controlTargetId()).thenReturn("gateway");
        var shadow = new ActionShadowAssessmentService(new InMemoryAgentActionRepository(2), control, records);
        var managed = new AgentActionService(new InMemoryAgentActionRepository(2), sessions, control,
                new ActionExecutorRegistry(List.of()), access, RiskBasedApprovalPolicy.defaults(),
                new InMemoryActionCommandRepository(10), true, null,
                new com.rover.agent.runtime.verification.BusinessEffectPolicy(true, 60_000, 5_000, 30_000,
                        2, 10, 0.9, 120_000, 0.01, 2_000), new InMemoryAutonomousActionBudgetRepository(),
                records, shadow);
        assertThrows(ActionRequestException.class, () -> managed.certify(subject.fingerprint(), 3,
                7 * 86400000L, 0, "admin"), "同一计划重复评估不得冒充覆盖两个计划版本");
        records.saveShadow(new ActionShadowRun("four", subject, "a4", 2, "3".repeat(64),
                ActionShadowRun.Outcome.PASS, "ok", 1, "2".repeat(64), now + 3, "admin"));
        var issued = managed.certify(subject.fingerprint(), 3, 7 * 86400000L, 0, "admin");
        assertEquals(1, issued.version());
        assertThrows(ActionRequestException.class, () -> managed.certify(subject.fingerprint(), 3,
                7 * 86400000L, 0, "admin"), "旧版本不能覆盖当前认证");
    }

    private static AgentAction action(ResourceRef resource) {
        long now = System.currentTimeMillis();
        var action = new AgentAction("action-shadow", "session", "incident", "task",
                ActionType.ADJUST_ROUTE_TARGET_WEIGHT, ActionStatus.PENDING_APPROVAL, "orders", "/orders",
                "order-service", "v2", 20, 0, 0, WeightRequestUnit.RAW_WEIGHT, 20, 0, 7,
                null, null, null, "owner", null, 0, List.of(), "quarantine", null, now, now, 0, resource, null);
        var provenance = new RemediationPlan.Provenance("RUNBOOK-1", "1", null,
                "plan-rule-v1", "RULE_EVIDENCE_GATE");
        var plan = RemediationPlan.forRouteWeight(action, "plan-shadow", 1, now + 60_000, List.of("ev-1"), provenance);
        var effect = new RemediationPlan.BusinessEffectSpec("effect-v1", "query-v1", "route:orders:all",
                60_000, 5_000, 30_000, 2, 10, 0.9, 120_000,
                List.of(new RemediationPlan.MetricCriterion("final5xxRate", "fraction", 0.01, 50)),
                List.of(new RemediationPlan.MetricGuardrail("p95", "ms", null, 2000.0)),
                RemediationPlan.MissingDataPolicy.INCONCLUSIVE_STOP_WRITES, "handoff");
        return action.withPlan(plan.withBusinessEffect(effect));
    }
}
