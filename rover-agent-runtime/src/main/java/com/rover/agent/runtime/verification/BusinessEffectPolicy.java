package com.rover.agent.runtime.verification;

import com.rover.agent.core.model.RemediationPlan;
import java.util.List;

/** Explicit operator-owned service thresholds; disabled by default until the service SLO is configured. */
public final class BusinessEffectPolicy {
    private final RemediationPlan.BusinessEffectSpec spec;

    public BusinessEffectPolicy(boolean enabled, long baselineMillis, long propagationGraceMillis, long windowMillis,
            int consecutiveWindows, long minimumRequestsPerWindow, double minimumCoverage,
            long maximumObservationMillis, double recovery5xxRate, double maximumP95Millis) {
        this.spec = enabled ? new RemediationPlan.BusinessEffectSpec(
                ActionEffectVerificationService.EFFECT_POLICY_VERSION,
                ActionEffectVerificationService.METRIC_QUERY_VERSION,
                "route:all-final-responses", baselineMillis, propagationGraceMillis, windowMillis,
                consecutiveWindows, minimumRequestsPerWindow, minimumCoverage, maximumObservationMillis,
                List.of(new RemediationPlan.MetricCriterion("final5xxRate", "fraction", recovery5xxRate, 50)),
                List.of(new RemediationPlan.MetricGuardrail("p95UpperBoundMillis", "milliseconds", null, maximumP95Millis)),
                RemediationPlan.MissingDataPolicy.INCONCLUSIVE_STOP_WRITES,
                "指标恶化、数据缺失或同期变化时停止归因并交由值班人员复核") : null;
    }

    public RemediationPlan attach(RemediationPlan plan) {
        return spec == null ? plan : plan.withBusinessEffect(new RemediationPlan.BusinessEffectSpec(
                spec.ruleVersion(), spec.queryTemplateVersion(),
                "route:" + plan.steps().get(0).routeId() + ":all-final-responses",
                spec.baselineMillis(), spec.propagationGraceMillis(), spec.windowMillis(), spec.consecutiveWindows(),
                spec.minimumRequestsPerWindow(), spec.minimumCoverage(), spec.maximumObservationMillis(),
                spec.criteria(), spec.guardrails(), spec.missingDataPolicy(), spec.escalation()));
    }

    public boolean enabled() { return spec != null; }
}
