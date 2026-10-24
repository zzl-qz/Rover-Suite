package com.rover.agent.runtime.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rover.agent.core.model.ActionEffectVerification;
import com.rover.agent.core.model.ActionStatus;
import com.rover.agent.core.model.ActionApproval;
import com.rover.agent.core.model.ActionType;
import com.rover.agent.core.model.AgentAction;
import com.rover.agent.core.model.EvidenceQuality;
import com.rover.agent.core.model.RemediationPlan;
import com.rover.agent.core.model.ResourceRef;
import com.rover.agent.core.model.ResourceScope;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.SessionStatus;
import com.rover.agent.core.model.TimeRange;
import com.rover.agent.core.model.WeightRequestUnit;
import com.rover.agent.core.port.MetricReadPort;
import com.rover.agent.core.port.TimelineReadPort;
import com.rover.agent.core.snapshot.RouteWindowSnapshot;
import com.rover.agent.core.timeline.ChangeEvent;
import com.rover.agent.core.timeline.TimelineSnapshot;
import com.rover.agent.runtime.repository.InMemoryActionEffectVerificationRepository;
import com.rover.agent.runtime.repository.InMemoryAgentActionRepository;
import com.rover.agent.runtime.repository.InMemoryAgentSessionRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ActionEffectVerificationServiceTest {
    private static final ResourceScope SCOPE = new ResourceScope("org", "ops", "test");
    private static final ResourceRef REF = new ResourceRef(SCOPE, "gateway", "GATEWAY_ROUTE", "r", "uid", 1);
    private static final String HASH = "a".repeat(64);
    private static final long APPLIED = 1_000_000;
    private static final RemediationPlan.BusinessEffectSpec SPEC = new RemediationPlan.BusinessEffectSpec(
            ActionEffectVerificationService.EFFECT_POLICY_VERSION, ActionEffectVerificationService.METRIC_QUERY_VERSION,
            "route:r:all-final-responses", 120_000, 5_000, 60_000, 2, 100, 0.95, 300_000,
            List.of(new RemediationPlan.MetricCriterion("final5xxRate", "fraction", 0.01, 50)),
            List.of(new RemediationPlan.MetricGuardrail("p95UpperBoundMillis", "milliseconds", null, 2_000.0)),
            RemediationPlan.MissingDataPolicy.INCONCLUSIVE_STOP_WRITES, "notify");

    @Test void persistsBaselineAndAdvancesEachUniqueWindowUntilTwoConsecutiveRecoveries() {
        Fixture f = new Fixture();
        f.metrics.when(snapshot(APPLIED - 120_000, APPLIED, 0.10, 500, 200));
        f.metrics.when(snapshot(APPLIED + 5_000, APPLIED + 65_000, 0.005, 400, 200));
        f.metrics.when(snapshot(APPLIED + 65_000, APPLIED + 125_000, 0.005, 400, 200));
        ActionEffectVerification queued = f.schedule();
        ActionEffectVerification observing = f.service.runDue(queued, APPLIED + 60_000);
        assertEquals(ActionEffectVerification.State.OBSERVING, observing.state());
        assertEquals(0, observing.windowsSeen());
        assertEquals(APPLIED + 65_000, observing.nextWindowAtMillis());
        ActionEffectVerification first = f.service.runDue(observing, APPLIED + 65_000);
        assertEquals(ActionEffectVerification.Outcome.PENDING, first.outcome());
        assertEquals(1, first.windowsSeen());
        ActionEffectVerification duplicate = f.service.runDue(first, APPLIED + 65_001);
        assertEquals(1, duplicate.windowsSeen(), "same window is not double counted");
        ActionEffectVerification second = f.service.runDue(duplicate, APPLIED + 125_000);
        assertEquals(ActionEffectVerification.State.COMPLETE, second.state());
        assertEquals(ActionEffectVerification.Outcome.RECOVERED, second.outcome());
        assertEquals(2, second.windowsSeen());
        assertEquals(ActionStatus.SUCCESS, f.actions.find("a1").orElseThrow().status());
    }

    @Test void persistsMissingAndInsufficientBaselineWithoutReportingHealthyZero() {
        Fixture f = new Fixture();
        f.metrics.when(snapshot(APPLIED - 120_000, APPLIED, 0, 500, 10));
        ActionEffectVerification item = f.service.runDue(f.schedule(), APPLIED + 10_000);
        assertEquals(ActionEffectVerification.Outcome.INSUFFICIENT_SAMPLE, item.outcome());
        assertEquals(ActionEffectVerification.State.COMPLETE, item.state());
        assertTrue(item.baselineValues().isEmpty());
    }

    @Test void contaminatedOrUnobservableTimelineStopsAttribution() {
        Fixture f = new Fixture();
        f.metrics.when(snapshot(APPLIED - 120_000, APPLIED, 0.1, 500, 200));
        f.metrics.when(snapshot(APPLIED + 5_000, APPLIED + 65_000, 0, 400, 200));
        f.timeline.result = new TimelineSnapshot(new TimeRange(APPLIED + 5_000, APPLIED + 65_000), APPLIED + 70_000,
                List.of(new ChangeEvent("e", SCOPE, "gateway", "release:ci", "e", "RELEASE_SUCCEEDED",
                        APPLIED + 30_000, APPLIED + 31_000, new TimeRange(APPLIED + 30_000, APPLIED + 30_000),
                        new ChangeEvent.Subject("r", "/orders", "uid", 1, List.of("orders"), null, null, null, null), Map.of(), List.of())),
                List.of(), false, List.of());
        ActionEffectVerification item = f.service.runDue(f.schedule(), APPLIED + 125_000);
        assertEquals(ActionEffectVerification.Outcome.CONTAMINATED, item.outcome());

        Fixture missingTimeline = new Fixture();
        missingTimeline.metrics.when(snapshot(APPLIED - 120_000, APPLIED, 0.1, 500, 200));
        missingTimeline.metrics.when(snapshot(APPLIED + 5_000, APPLIED + 65_000, 0, 400, 200));
        missingTimeline.timeline.failure = new IllegalStateException("offline");
        ActionEffectVerification missing = missingTimeline.service.runDue(missingTimeline.schedule(), APPLIED + 125_000);
        assertEquals(ActionEffectVerification.Outcome.MISSING_DATA, missing.outcome());
    }

    @Test void compensationHasItsOwnRecordAndPersistentDueQueueCanResume() {
        Fixture f = new Fixture(ActionEffectVerification.Phase.COMPENSATION);
        f.metrics.when(snapshot(APPLIED - 120_000, APPLIED, 0.1, 500, 200));
        ActionEffectVerification item = f.schedule();
        assertEquals(ActionEffectVerification.Phase.COMPENSATION, item.phase());
        assertEquals(1, f.repository.due(Long.MAX_VALUE, 10).size());
        assertNotNull(f.repository.find("a1", ActionEffectVerification.Phase.COMPENSATION).orElse(null));
        assertFalse(f.repository.find("a1", ActionEffectVerification.Phase.APPLY).isPresent());
    }

    @Test void incompleteTimelineIsNotInterpretedAsNoConcurrentChange() {
        Fixture f = new Fixture();
        f.metrics.when(snapshot(APPLIED - 120_000, APPLIED, 0.1, 500, 200));
        f.metrics.when(snapshot(APPLIED + 5_000, APPLIED + 65_000, 0, 400, 200));
        f.timeline.result = new TimelineSnapshot(new TimeRange(APPLIED + 5_000, APPLIED + 65_000), APPLIED + 70_000,
                List.of(), List.of(), false, List.of("rover.routes 当前采集失败或已过期。"));
        ActionEffectVerification item = f.service.runDue(f.schedule(), APPLIED + 125_000);
        assertEquals(ActionEffectVerification.Outcome.MISSING_DATA, item.outcome());
    }

    private static RouteWindowSnapshot snapshot(long from, long to, double rate, double p95, long requests) {
        long failures = Math.round(rate * requests);
        return new RouteWindowSnapshot("r", new TimeRange(from, to), new TimeRange(from, to), to + 1,
                EvidenceQuality.AVAILABLE, requests, failures, "ALL_FINAL_REQUESTS",
                Map.of("coverageRatio", 1.0, "droppedDimensionRequests", 0, "incompleteResponses", 0,
                        "p95UpperBoundMillis", p95), List.of());
    }

    private static final class Fixture {
        final InMemoryAgentActionRepository actions = new InMemoryAgentActionRepository(10);
        final InMemoryAgentSessionRepository sessions = new InMemoryAgentSessionRepository(10);
        final InMemoryActionEffectVerificationRepository repository = new InMemoryActionEffectVerificationRepository(10);
        final FakeMetrics metrics = new FakeMetrics();
        final FakeTimeline timeline = new FakeTimeline();
        final ActionEffectVerification.Phase phase;
        AgentAction action;
        final ActionEffectVerificationService service;

        Fixture() { this(ActionEffectVerification.Phase.APPLY); }
        Fixture(ActionEffectVerification.Phase phase) {
            this.phase = phase;
            sessions.save(new Session("s", "alice", "route", null, SessionStatus.ACTIVE, 1, 1, List.of()).withScope(SCOPE, "test"));
            AgentAction raw = new AgentAction("a1", "s", null, "task", ActionType.ADJUST_ROUTE_TARGET_WEIGHT,
                    ActionStatus.PENDING_APPROVAL,
                    "r", "/orders", "orders", "v2", 10, 0, 0, WeightRequestUnit.RAW_WEIGHT,
                    50, 0, 1, null, null, null,
                    "alice", null, 0, List.of(), "quarantine", null,
                    APPLIED - 200_000, APPLIED - 200_000, 0, REF, null);
            RemediationPlan base = RemediationPlan.forRouteWeight(raw, "p", 1, APPLIED + 1_000_000);
            action = raw.withPlan(base.withBusinessEffect(SPEC));
            actions.save(action);
            action = actions.transition("a1", ActionStatus.PENDING_APPROVAL, a -> new AgentAction(a.actionId(), a.sessionId(),
                    a.incidentId(), a.taskId(), a.type(), phase == ActionEffectVerification.Phase.APPLY
                            ? ActionStatus.SUCCESS : ActionStatus.ROLLED_BACK, a.routeId(), a.businessPrefix(),
                    a.serviceName(), a.group(), a.beforeWeight(), a.desiredWeight(), a.requestedValue(), a.requestedUnit(),
                    a.beforeTrafficPercent(), a.desiredTrafficPercent(), a.expectedRevision(), "apply-op", 2,
                    phase == ActionEffectVerification.Phase.APPLY ? null : "rollback-op",
                    "alice", null, 0, List.of(), a.impact(), null, a.createdAtMillis(), APPLIED,
                    a.version(), REF, a.plan())).orElseThrow();
            action = actions.find("a1").orElseThrow();
            service = new ActionEffectVerificationService(actions, sessions, repository, metrics, timeline);
        }
        ActionEffectVerification schedule() {
            service.schedule(action, phase, APPLIED + 10_000);
            return repository.find("a1", phase).orElseThrow();
        }
    }

    private static final class FakeMetrics implements MetricReadPort {
        private final java.util.Map<TimeRange, RouteWindowSnapshot> windows = new java.util.HashMap<>();
        void when(RouteWindowSnapshot window) { windows.put(window.requestedRange(), window); }
        @Override public com.rover.agent.core.snapshot.GatewayMetricSnapshot gatewayWindow(int seconds) { throw new UnsupportedOperationException(); }
        @Override public RouteWindowSnapshot routeWindow(String routeId, TimeRange range) {
            RouteWindowSnapshot found = windows.get(range);
            if (found == null) throw new IllegalStateException("no metric window " + range);
            return found;
        }
    }

    private static final class FakeTimeline implements TimelineReadPort {
        TimelineSnapshot result = new TimelineSnapshot(new TimeRange(APPLIED, APPLIED + 1), APPLIED + 1,
                List.of(), List.of(), false, List.of());
        RuntimeException failure;
        @Override public TimelineSnapshot query(com.rover.agent.core.timeline.TimelineQuery query) {
            if (failure != null) throw failure;
            return result;
        }
    }
}
