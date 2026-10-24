package com.rover.agent.core.investigation;

import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.Hypothesis;
import com.rover.agent.core.model.Verdict;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 可复核的诊断表达：模型选择目录项，数值、判定和边界始终来自取证程序。 */
public final class DiagnosticReport {
    public record Fact(String id, String evidenceId, String text, boolean observation) { }
    private final List<Fact> facts = new ArrayList<>();
    private final List<Hypothesis> hypotheses;

    public DiagnosticReport(List<Evidence> evidence, List<Hypothesis> hypotheses) {
        this.hypotheses = hypotheses == null ? List.of() : List.copyOf(hypotheses);
        // 先列同窗口的逐版本观测，防止只展示一台实例而隐去对照组。
        for (Evidence item : evidence) {
            Object populations = item.envelope().facts().get("samplePopulations");
            if (populations instanceof List<?> rows) {
                for (Object raw : rows) {
                    if (raw instanceof Map<?, ?> row) {
                        add(item, "保留的追踪样本：路由=" + text(row.get("routeId")) + "，服务=" + text(row.get("serviceName")) + "，版本=" + text(row.get("group"))
                                + "，实例=" + text(row.get("hostPort")) + "，请求=" + row.get("sampleRequests")
                                + "，5xx=" + row.get("sample5xx") + "，sample5xxRate=" + row.get("sample5xxRate")
                                + "，状态分布=" + text(row.get("statuses")) + "，响应来源=" + text(row.get("responseOrigins"))
                                + "，故障阶段样本=" + phaseCounts(item, row)
                                + "；调查窗口=" + item.envelope().requestedRange().describe() + "；质量=" + item.envelope().qualityStatus()
                                + "。仅代表保留样本，不代表完整流量或版本整体健康。", true);
                    }
                }
            }
            if ("gateway.final-responses".equals(item.envelope().sourceId())) {
                add(item, "最终响应窗口观测：" + text(item.summary()) + "；质量=" + item.envelope().qualityStatus(), true);
            }
        }
        for (Evidence item : evidence) {
            if ("rover.change-timeline".equals(item.envelope().sourceId())) {
                Object events = item.envelope().facts().get("events");
                int changes = 0;
                if (events instanceof List<?> rows) {
                    for (Object raw : rows) {
                        if (!(raw instanceof Map<?, ?> event) || String.valueOf(event.get("kind")).endsWith("_BASELINE")) { continue; }
                        if (changes++ >= 4) { break; }
                        Object at = event.get("occurredAtMillis");
                        String time = at instanceof Number n ? Instant.ofEpochMilli(n.longValue()).toString() : "具体发生时间未知，仅有观测区间";
                        add(item, "已记录变更：" + text(event.get("kind")) + "；源时间=" + time
                                + "；资源=" + text(event.get("subject")) + "；来源声明=" + text(event.get("facts"))
                                + "。来源声明、名称及时间先后均不能证明因果。", false);
                    }
                }
                add(item, "变更时间线为部分观测；BASELINE 仅为首次观测。空列表、没有观察到变化，均不能证明没有变更或配置正常。", false);
            } else if (!item.envelope().facts().containsKey("samplePopulations")
                    && !"gateway.final-responses".equals(item.envelope().sourceId())) {
                String prefix = switch (item.type()) {
                    case ROUTE, INSTANCE, CONFIG -> "状态参考（仅在其取证时间有效，不证明历史状态）：";
                    default -> "来源记录（不作因果推断）：";
                };
                add(item, prefix + text(item.title()) + "：" + text(item.summary()) + "；质量=" + item.envelope().qualityStatus(), false);
            }
        }
    }

    private void add(Evidence evidence, String detail, boolean observation) {
        if (facts.size() < 24) { facts.add(new Fact("F" + (facts.size() + 1), evidence.evidenceId(), detail, observation)); }
    }

    private static Map<String, Long> phaseCounts(Evidence evidence, Map<?, ?> population) {
        Map<String, Long> counts = new java.util.TreeMap<>();
        Object raw = evidence.envelope().facts().get("rows");
        if (raw instanceof List<?> rows) {
            for (Object value : rows) {
                if (!(value instanceof Map<?, ?> row)) { continue; }
                boolean same = List.of("routeId", "resourceUid", "generation", "serviceName", "group", "hostPort").stream()
                        .allMatch(key -> java.util.Objects.equals(row.get(key), population.get(key)));
                if (same) { counts.merge(text(row.get("failurePhase")), 1L, Long::sum); }
            }
        }
        return counts;
    }

    public List<Fact> facts() { return List.copyOf(facts); }

    /** 所有 ID 必须来自本次目录；不接收模型编写的结论、数字、建议动作或引用文本。 */
    public String render(List<String> selectedFacts) {
        Set<String> selected = new LinkedHashSet<>(selectedFacts);
        if (selected.size() > 12 || selected.size() != selectedFacts.size()
                || selected.stream().anyMatch(id -> facts.stream().noneMatch(f -> f.id().equals(id)))) {
            throw new IllegalArgumentException("诊断引用不在本次事实目录中");
        }
        StringBuilder out = new StringBuilder("根因尚未确认。以下区分观测事实、已验证假设与证据缺口；故障来源和业务根因不是同一个结论。\n\n已采集事实：\n");
        for (Fact fact : facts) {
            if (fact.observation() || selected.contains(fact.id())) {
                out.append("- ").append(fact.text()).append(" [evidenceId=").append(fact.evidenceId()).append("]\n");
            }
        }
        out.append("\n假设验证：\n");
        for (Hypothesis h : hypotheses) {
            out.append("- ").append(h.id()).append(' ').append(text(h.statement())).append("：")
                    .append(h.status() == Verdict.CONFIRMED ? "已确认该项观测" : h.status() == Verdict.REJECTED ? "在证据口径内排除该项" : "无法确认")
                    .append("；").append(text(h.detail()));
            if (!h.supportingEvidenceIds().isEmpty()) { out.append("；支持=").append(h.supportingEvidenceIds()); }
            if (!h.counterEvidenceIds().isEmpty()) { out.append("；反证=").append(h.counterEvidenceIds()); }
            out.append('\n');
        }
        out.append("\n缺失证据与下一步：\n");
        hypotheses.stream().filter(h -> h.status() == Verdict.UNKNOWN).limit(8).forEach(h -> {
            out.append("- ").append(h.id()).append("：").append(text(String.join("；", h.missingEvidence())));
            if (!h.nextCapabilities().isEmpty()) { out.append("；可补充的只读检查=").append(h.nextCapabilities()); }
            out.append('\n');
        });
        return out.append("尚未执行修复或验证业务恢复；提出变更前仍需核对资源身份、其余实例容量和审批条件。不能保证单次缩流即可恢复。") .toString();
    }

    public String renderDefault() {
        return render(facts.stream().limit(8).map(Fact::id).toList());
    }

    private static String text(Object value) {
        String text = value == null ? "未知" : String.valueOf(value).replaceAll("[\\r\\n\\t]+", " ")
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("`", "\\`");
        return text.length() > 420 ? text.substring(0, 420) + "…（已截断）" : text;
    }
}
