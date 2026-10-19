package com.rover.agent.core.investigation;

import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.EvidenceQuality;
import com.rover.agent.core.model.Hypothesis;
import com.rover.agent.core.model.Verdict;
import java.util.ArrayList;
import java.util.List;

/** 将规则判定绑定到本次实际取得的证据；缺少可用引用不能输出确认或排除。 */
public final class HypothesisAssessment {
    private HypothesisAssessment() { }

    public static Findings evaluate(FindingsInput input, List<Evidence> evidence) {
        Findings judged = InvestigationRules.evaluate(input);
        List<Hypothesis> items = new ArrayList<>();
        boolean downgraded = false;
        for (Hypothesis item : judged.hypotheses()) {
            List<String> ids = evidence.stream().filter(e -> relevant(item.id(), e))
                    .filter(e -> sameTarget(input, e))
                    .filter(e -> !item.id().equals("H6") || input.routeUpstreams() == null
                            || e.metadata().get("hostPort") == null || input.routeUpstreams().stream().anyMatch(row ->
                            e.metadata().get("hostPort").equals(row.hostPort()) && row.windowRequests() >= InvestigationRules.MIN_INSTANCE_SAMPLE
                                    && (item.status() != Verdict.CONFIRMED || row.status5xx() > 0)))
                    .filter(HypothesisAssessment::usable).map(Evidence::evidenceId).distinct().toList();
            Verdict status = ids.isEmpty() ? Verdict.UNKNOWN : item.status();
            downgraded |= status != item.status();
            String detail = status != item.status() ? "缺少本次调查中可引用的有效证据；" + item.detail() : item.detail();
            List<AgentCapability> next = status == Verdict.UNKNOWN ? next(item.id(), input) : List.of();
            items.add(new Hypothesis(item.id(), item.statement(), status, detail, item.sources(),
                    status == Verdict.CONFIRMED ? ids : List.of(), status == Verdict.REJECTED ? ids : List.of(),
                    status == Verdict.UNKNOWN ? List.of(detail) : List.of(), next));
        }
        boolean causeObserved = items.stream().anyMatch(h -> h.status() == Verdict.CONFIRMED && !h.id().equals("H5"));
        if (!causeObserved) {
            List<String> changes = evidence.stream().filter(HypothesisAssessment::usable).filter(e -> sameTarget(input, e))
                    .filter(e -> "rover.change-timeline".equals(e.envelope().sourceId()))
                    .filter(e -> e.envelope().facts().get("events") instanceof List<?> rows && rows.stream().anyMatch(raw ->
                            raw instanceof java.util.Map<?, ?> row && row.get("kind") instanceof String kind && !kind.endsWith("_BASELINE")))
                    .map(Evidence::evidenceId).toList();
            items.add(new Hypothesis("H7", "故障与近期发布或配置、实例变化有关", Verdict.UNKNOWN,
                    "变更记录仅提供关联线索，时间接近不能证明因果关系", List.of("/api/agent/timeline"), changes, List.of(),
                    List.of("需要同资源、同故障窗口的变更记录与请求或应用内部直接证据；时间线不保证完整覆盖"),
                    List.of(AgentCapability.CHANGE_TIMELINE_QUERY, AgentCapability.CONFIG_READ)));
        }
        return new Findings(List.copyOf(items), downgraded ? "有效证据不足，无法确认故障原因。" : judged.summary(),
                downgraded ? com.rover.agent.core.model.Confidence.LOW : judged.confidence());
    }

    private static boolean sameTarget(FindingsInput input, Evidence evidence) {
        var context = evidence.envelope().context();
        if (context == null || context.target() == null) { return true; }
        var target = context.target();
        return target.type() != com.rover.agent.core.model.TargetType.ROUTE || target.value().equals(input.path());
    }

    private static boolean usable(Evidence e) {
        EvidenceQuality q = e.envelope().qualityStatus();
        return q != EvidenceQuality.UNAVAILABLE && q != EvidenceQuality.OUT_OF_RANGE && q != EvidenceQuality.STALE;
    }

    private static boolean relevant(String id, Evidence e) {
        String ref = e.rawReference() == null ? "" : e.rawReference();
        return switch (id) {
            case "H1" -> "/api/routes".equals(ref) && "路由匹配".equals(e.title());
            case "H3", "H4" -> "/api/instances".equals(ref);
            case "H5" -> ref.startsWith("/api/traces");
            case "H6" -> ref.startsWith("/api/metrics/routes") && "上游实例窗口观测".equals(e.title());
            default -> false;
        };
    }

    private static List<AgentCapability> next(String id, FindingsInput input) {
        boolean historical = !input.timeRange().isUnspecified();
        if (historical && !id.equals("H5")) {
            return List.of(AgentCapability.LOG_QUERY, AgentCapability.CHANGE_TIMELINE_QUERY);
        }
        return switch (id) {
            case "H1" -> List.of(AgentCapability.ROUTE_QUERY);
            case "H3", "H4" -> List.of(AgentCapability.INSTANCE_QUERY);
            case "H5" -> List.of(AgentCapability.TRACE_QUERY, AgentCapability.LOG_QUERY);
            case "H6" -> List.of(AgentCapability.GATEWAY_METRICS_QUERY);
            default -> List.of();
        };
    }
}
