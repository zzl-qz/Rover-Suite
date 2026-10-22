package com.rover.agent.runtime.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.model.ActionEffectVerification;
import com.rover.agent.core.model.EvidenceQuality;
import com.rover.agent.core.model.RemediationPlan;
import com.rover.agent.core.model.TimeRange;
import com.rover.agent.core.snapshot.RouteWindowSnapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BusinessEffectEvaluatorTest {
    private static final RemediationPlan.BusinessEffectSpec SPEC = new RemediationPlan.BusinessEffectSpec(
            ActionEffectVerificationService.EFFECT_POLICY_VERSION, ActionEffectVerificationService.METRIC_QUERY_VERSION,
            "route:r:all-final-responses", 120_000, 5_000, 60_000, 2, 100, 0.95, 300_000,
            List.of(new RemediationPlan.MetricCriterion("final5xxRate", "fraction", 0.01, 50)),
            List.of(new RemediationPlan.MetricGuardrail("p95UpperBoundMillis", "milliseconds", null, 2_000.0)),
            RemediationPlan.MissingDataPolicy.INCONCLUSIVE_STOP_WRITES, "通知值班人员");

    private final BusinessEffectEvaluator evaluator = new BusinessEffectEvaluator();

    @Test void classifiesRecoveredImprovedNotRecoveredAndWorsenedSeparately() {
        assertEquals(ActionEffectVerification.Outcome.RECOVERED, evaluate(0, 100).outcome());
        assertEquals(ActionEffectVerification.Outcome.IMPROVED, evaluate(0.04, 100).outcome());
        assertEquals(ActionEffectVerification.Outcome.NOT_RECOVERED, evaluate(0.09, 100).outcome());
        assertEquals(ActionEffectVerification.Outcome.WORSENED, evaluate(0.12, 100).outcome());
    }

    @Test void missingSamplesCoverageAndHealthBaselineNeverBecomeRecovery() {
        assertEquals(ActionEffectVerification.Outcome.INSUFFICIENT_SAMPLE,
                evaluator.evaluate(snapshot(0.0, 10, 1_000), SPEC, "r", 1_000, 2_000, Map.of("final5xxRate", 0.1)).outcome());
        assertEquals(ActionEffectVerification.Outcome.MISSING_DATA,
                evaluator.evaluate(new RouteWindowSnapshot("r", new TimeRange(1_000, 2_000), new TimeRange(1_000, 1_900),
                        2_001, EvidenceQuality.AVAILABLE, 200, 0, "ALL_FINAL_REQUESTS",
                        Map.of("coverageRatio", 0.9, "droppedDimensionRequests", 0, "incompleteResponses", 0,
                                "p95UpperBoundMillis", 500), List.of()), SPEC, "r", 1_000, 2_000,
                        Map.of("final5xxRate", 0.1, "p95UpperBoundMillis", 500.0)).outcome());
        assertFalse(evaluator.baselineAlreadyHealthy(Map.of("final5xxRate", 0.1, "p95UpperBoundMillis", 100.0), SPEC));
        assertTrue(evaluator.baselineAlreadyHealthy(Map.of("final5xxRate", 0.0, "p95UpperBoundMillis", 100.0), SPEC));
    }

    @Test void baselineRequiresFullWindowAndEveryPlanMetric() {
        assertThrows(IllegalArgumentException.class, () -> evaluator.baseline(
                snapshot(0.1, 200, 1_500), SPEC, "r", 1_000, 2_000));
        assertThrows(IllegalArgumentException.class, () -> evaluator.baseline(
                new RouteWindowSnapshot("r", new TimeRange(1_000, 2_000), new TimeRange(1_000, 2_000),
                        2_000, EvidenceQuality.AVAILABLE, 200, 20, "ALL_FINAL_REQUESTS",
                        Map.of("coverageRatio", 1.0, "droppedDimensionRequests", 0, "incompleteResponses", 0), List.of()),
                SPEC, "r", 1_000, 2_000));
    }

    @Test void zeroBaselineCanBeEvaluatedWithoutDivisionOrInventedImprovement() {
        var result = evaluator.evaluate(snapshot(0, 200, 1_000), SPEC, "r", 1_000, 2_000,
                Map.of("final5xxRate", 0.0, "p95UpperBoundMillis", 500.0));
        assertEquals(ActionEffectVerification.Outcome.RECOVERED, result.outcome());
        assertTrue(result.limitations().stream().anyMatch(item -> item.contains("基线已为零")));
    }

    private ActionEffectVerification.Window evaluate(double rate, long requests) {
        return evaluator.evaluate(snapshot(rate, requests, 1_000, 1_000), SPEC, "r", 1_000, 2_000,
                Map.of("final5xxRate", 0.10, "p95UpperBoundMillis", 500.0));
    }

    private static RouteWindowSnapshot snapshot(double rate, long requests, long coverageFrom) {
        return snapshot(rate, requests, coverageFrom, 500);
    }

    private static RouteWindowSnapshot snapshot(double rate, long requests, long coverageFrom, double p95) {
        long failures = Math.round(rate * requests);
        return new RouteWindowSnapshot("r", new TimeRange(1_000, 2_000), new TimeRange(coverageFrom, 2_000),
                2_100, EvidenceQuality.AVAILABLE, requests, failures, "ALL_FINAL_REQUESTS",
                Map.of("coverageRatio", (2_000 - coverageFrom) / 1_000.0, "droppedDimensionRequests", 0,
                        "incompleteResponses", 0, "p95UpperBoundMillis", p95), List.of());
    }
}
