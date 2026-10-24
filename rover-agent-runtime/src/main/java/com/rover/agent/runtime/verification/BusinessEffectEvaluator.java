package com.rover.agent.runtime.verification;

import com.rover.agent.core.model.ActionEffectVerification;
import com.rover.agent.core.model.RemediationPlan;
import com.rover.agent.core.snapshot.RouteWindowSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic evaluator for route-level final response error rate and P95 latency. */
public final class BusinessEffectEvaluator {

    public boolean baselineAlreadyHealthy(Map<String, Double> baseline, RemediationPlan.BusinessEffectSpec spec) {
        return spec.criteria().stream().allMatch(criterion -> baseline.get(criterion.metric()) != null
                && baseline.get(criterion.metric()) <= criterion.recoveryUpperBound())
                && spec.guardrails().stream().allMatch(guard -> {
                    Double value = baseline.get(guard.metric());
                    return value != null && (guard.minimumValue() == null || value >= guard.minimumValue())
                            && (guard.maximumValue() == null || value <= guard.maximumValue());
                });
    }

    public ActionEffectVerification.Window evaluate(RouteWindowSnapshot window, RemediationPlan.BusinessEffectSpec spec,
                                                    String expectedRouteId, long expectedFromMillis, long expectedToMillis,
                                                    Map<String, Double> baseline) {
        if (window == null || window.facts() == null || window.limitations() == null || window.requestedRange() == null) {
            return missing(expectedFromMillis, expectedToMillis, 0, "指标窗口结构不完整");
        }
        List<String> limitations = new ArrayList<>(window.limitations());
        if (window.qualityStatus() != com.rover.agent.core.model.EvidenceQuality.AVAILABLE
                || !expectedRouteId.equals(window.routeId())
                || window.requestedRange() == null
                || window.requestedRange().fromMillis() != expectedFromMillis
                || window.requestedRange().toMillis() != expectedToMillis
                || !"ALL_FINAL_REQUESTS".equals(window.samplingMode())
                || window.coverageRange() == null || window.coverageRange().fromMillis() > expectedFromMillis
                || window.coverageRange().toMillis() < expectedToMillis
                || coverage(window, expectedFromMillis, expectedToMillis) < spec.minimumCoverage()
                || window.observedAtMillis() < window.coverageRange().toMillis()
                || window.observedAtMillis() - window.coverageRange().toMillis() > 120_000
                || window.finalRequests() < 0 || window.final5xx() < 0 || window.final5xx() > window.finalRequests()) {
            limitations.add("指标窗口未完整覆盖请求时段、数据不新鲜或质量不是 AVAILABLE");
            return result(expectedFromMillis, expectedToMillis, ActionEffectVerification.Outcome.MISSING_DATA,
                    window, Map.of(), limitations);
        }
        if (window.finalRequests() < spec.minimumRequestsPerWindow()) {
            limitations.add("窗口请求数低于最低样本量 " + spec.minimumRequestsPerWindow());
            return result(expectedFromMillis, expectedToMillis, ActionEffectVerification.Outcome.INSUFFICIENT_SAMPLE,
                    window, Map.of(), limitations);
        }
        double coverage = coverage(window, expectedFromMillis, expectedToMillis);
        Double dropped = numeric(window.facts(), "droppedDimensionRequests");
        Double incomplete = numeric(window.facts(), "incompleteResponses");
        if (!Double.isFinite(coverage) || coverage < spec.minimumCoverage() || dropped == null || dropped > 0
                || incomplete == null || incomplete > 0) {
            limitations.add("窗口覆盖率不足、维度截断或最终响应不完整");
            return new ActionEffectVerification.Window(expectedFromMillis, expectedToMillis,
                    ActionEffectVerification.Outcome.MISSING_DATA, window.finalRequests(),
                    Math.max(0, Math.min(1, coverage)), Map.of(), limitations);
        }
        Map<String, Double> values = routeMetrics(window);
        boolean guardrailViolated = false;
        boolean worsened = false;
        for (RemediationPlan.MetricGuardrail guard : spec.guardrails()) {
            Double observed = values.get(guard.metric());
            if (observed == null && guard.metric().equals("p95UpperBoundMillis")) {
                Double upperBound = numeric(window.facts(), "p95UpperBoundMillis");
                if (upperBound != null) { observed = upperBound; }
            }
            if (observed == null) {
                limitations.add("缺少护栏指标：" + guard.metric());
                return new ActionEffectVerification.Window(expectedFromMillis, expectedToMillis,
                        ActionEffectVerification.Outcome.MISSING_DATA, window.finalRequests(), coverage, values, limitations);
            }
            boolean violated = guard.minimumValue() != null && observed < guard.minimumValue()
                    || guard.maximumValue() != null && observed > guard.maximumValue();
            if (violated) {
                guardrailViolated = true;
                Double before = baseline.get(guard.metric());
                worsened |= before != null && (guard.minimumValue() != null && before >= guard.minimumValue()
                        || guard.maximumValue() != null && before <= guard.maximumValue());
            }
        }
        boolean recovered = !guardrailViolated;
        boolean improved = false;
        for (RemediationPlan.MetricCriterion criterion : spec.criteria()) {
            Double current = values.get(criterion.metric()), before = baseline.get(criterion.metric());
            if (current == null || before == null) {
                limitations.add("缺少判定指标或变更前基线：" + criterion.metric());
                return new ActionEffectVerification.Window(expectedFromMillis, expectedToMillis,
                        ActionEffectVerification.Outcome.MISSING_DATA, window.finalRequests(), coverage, values, limitations);
            }
            recovered &= current <= criterion.recoveryUpperBound();
            improved |= before > 0 && criterion.minimumRelativeImprovementPercent() > 0
                    && current <= before * (1.0 - criterion.minimumRelativeImprovementPercent() / 100.0);
            worsened |= current > before;
        }
        if (spec.criteria().stream().anyMatch(criterion -> criterion.minimumRelativeImprovementPercent() > 0
                && baseline.get(criterion.metric()) == 0)) {
            limitations.add("变更前基线已为零，不能计算相对改善比例；仅按恢复阈值判定");
        }
        ActionEffectVerification.Outcome outcome = recovered ? ActionEffectVerification.Outcome.RECOVERED
                : worsened ? ActionEffectVerification.Outcome.WORSENED
                : guardrailViolated ? ActionEffectVerification.Outcome.NOT_RECOVERED
                : improved ? ActionEffectVerification.Outcome.IMPROVED
                : ActionEffectVerification.Outcome.NOT_RECOVERED;
        return new ActionEffectVerification.Window(expectedFromMillis, expectedToMillis, outcome,
                window.finalRequests(), coverage, values, limitations);
    }

    public Map<String, Double> routeMetrics(RouteWindowSnapshot window) {
        Map<String, Double> values = new LinkedHashMap<>();
        if (window.finalRequests() > 0) {
            values.put("final5xxRate", window.final5xx() / (double) window.finalRequests());
            Double p95 = numeric(window.facts(), "p95UpperBoundMillis");
            if (p95 != null && Double.isFinite(p95) && p95 >= 0) { values.put("p95UpperBoundMillis", p95); }
            Double incomplete = numeric(window.facts(), "incompleteResponses");
            if (incomplete != null) { values.put("incompleteResponses", incomplete); }
        }
        return Map.copyOf(values);
    }

    private static ActionEffectVerification.Window result(long from, long to,
            ActionEffectVerification.Outcome outcome, RouteWindowSnapshot window,
            Map<String, Double> values, List<String> limitations) {
        return new ActionEffectVerification.Window(from, to, outcome, window.finalRequests(), 0, values, limitations);
    }

    public Map<String, Double> baseline(RouteWindowSnapshot window, RemediationPlan.BusinessEffectSpec spec,
                                        String expectedRouteId, long fromMillis, long toMillis) {
        if (window == null || window.facts() == null || window.requestedRange() == null || window.limitations() == null
                || window.qualityStatus() != com.rover.agent.core.model.EvidenceQuality.AVAILABLE
                || !expectedRouteId.equals(window.routeId())
                || !new com.rover.agent.core.model.TimeRange(fromMillis, toMillis).equals(window.requestedRange())
                || window.coverageRange() == null || window.coverageRange().fromMillis() > fromMillis
                || window.coverageRange().toMillis() < toMillis
                || !"ALL_FINAL_REQUESTS".equals(window.samplingMode())
                || window.finalRequests() < 0 || window.final5xx() < 0 || window.final5xx() > window.finalRequests()) {
            throw new IllegalArgumentException("变更前基线未完整覆盖指定路由和窗口");
        }
        if (window.finalRequests() < spec.minimumRequestsPerWindow()) {
            throw new InsufficientSampleException("变更前基线请求数低于最低样本量 " + spec.minimumRequestsPerWindow());
        }
        double coverage = coverage(window, fromMillis, toMillis);
        Double dropped = numeric(window.facts(), "droppedDimensionRequests");
        Double incomplete = numeric(window.facts(), "incompleteResponses");
        if (!Double.isFinite(coverage) || coverage < spec.minimumCoverage() || dropped == null || dropped > 0
                || incomplete == null || incomplete > 0) {
            throw new IllegalArgumentException("变更前基线覆盖率不足、维度截断或最终响应不完整");
        }
        Map<String, Double> values = routeMetrics(window);
        if (spec.criteria().stream().anyMatch(metric -> !values.containsKey(metric.metric()))
                || spec.guardrails().stream().anyMatch(metric -> !values.containsKey(metric.metric()))) {
            throw new IllegalArgumentException("变更前基线缺少计划要求的业务指标");
        }
        return values;
    }

    private static double coverage(RouteWindowSnapshot window, long from, long to) {
        if (window.coverageRange() == null || to <= from) { return 0; }
        long covered = Math.max(0, Math.min(to, window.coverageRange().toMillis())
                - Math.max(from, window.coverageRange().fromMillis()));
        return covered / (double) (to - from);
    }

    private static ActionEffectVerification.Window missing(long from, long to, long requests, String reason) {
        return new ActionEffectVerification.Window(from, to, ActionEffectVerification.Outcome.MISSING_DATA,
                requests, 0, Map.of(), List.of(reason));
    }

    private static Double numeric(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    public static final class InsufficientSampleException extends IllegalArgumentException {
        public InsufficientSampleException(String message) { super(message); }
    }
}
