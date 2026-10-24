package com.rover.agent.runtime.action;

import com.rover.agent.core.investigation.Findings;
import com.rover.agent.core.model.AgentAction;
import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.EvidenceQuality;
import com.rover.agent.core.model.Hypothesis;
import com.rover.agent.core.model.InvestigationContext;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.TargetType;
import com.rover.agent.core.model.TimeRange;
import com.rover.agent.core.model.Verdict;
import com.rover.agent.core.port.AgentAccess;
import com.rover.agent.runtime.task.InvestigationTask;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 只有高质量、当前且可追溯的上游故障证据才能生成待审批版本隔离计划。 */
public final class EvidenceGatedPlanProposer {

    private static final Logger log = LoggerFactory.getLogger(EvidenceGatedPlanProposer.class);
    private static final String UPSTREAM_SOURCE = "gateway.route-upstreams";
    private static final String UPSTREAM_TITLE = "上游实例窗口观测";
    private static final int MIN_GROUP_REQUESTS = 20;
    private static final int MIN_GROUP_5XX = 5;
    private static final double MIN_GROUP_ERROR_RATE = 0.20;
    private static final int MIN_FAILED_HOSTS_PERCENT = 50;
    private static final long MAX_EVIDENCE_AGE_MILLIS = 120_000L;

    private EvidenceGatedPlanProposer() { }

    public static Decision propose(InvestigationTask task, String path, Findings findings,
                                   List<Evidence> evidence, List<String> limitations,
                                   boolean proposalAlreadyAttempted, AgentActionService actions) {
        if (proposalAlreadyAttempted) {
            return Decision.rejected("本次任务已处理用户明确要求的变更，不重复生成自动候选计划", false);
        }
        if (task == null || task.timeRange() == null || !task.timeRange().isUnspecified()) {
            return Decision.rejected("本策略只使用当前滚动窗口指标，历史调查不能生成自动候选计划", false);
        }
        if (findings == null) {
            return Decision.rejected("未形成可验证的路径诊断结论，无法评估自动处置", false);
        }
        Hypothesis h6 = findings.hypotheses().stream().filter(item -> "H6".equals(item.id())).findFirst().orElse(null);
        if (h6 == null || h6.status() != Verdict.CONFIRMED || h6.supportingEvidenceIds().isEmpty()) {
            return Decision.rejected("没有由本次证据确认的上游版本异常（H6），不创建权重隔离计划", false);
        }
        if (path == null || path.isBlank()) {
            return Decision.rejected("缺少本次调查的精确请求路径，无法绑定变更目标", false);
        }
        if (limitations != null && limitations.stream().anyMatch(text -> text != null && text.contains("只列举请求数最多的"))) {
            return Decision.rejected("上游实例证据被截断，无法确认版本组的完整错误比例", false);
        }

        List<Evidence> rows = evidence == null ? List.of() : evidence.stream()
                .filter(item -> UPSTREAM_TITLE.equals(item.title())
                        && UPSTREAM_SOURCE.equals(item.envelope().sourceId()))
                .toList();
        if (rows.isEmpty()) {
            return Decision.rejected("缺少带结构化计数的上游实例证据，不能仅凭结论文字生成计划", false);
        }

        long now = System.currentTimeMillis();
        Map<MetricKey, MetricRow> byKey = new LinkedHashMap<>();
        for (Evidence item : rows) {
            MetricRow metric = decode(item, task, path, now);
            if (metric == null) {
                return Decision.rejected("上游指标缺少完整的路由、版本、样本或新鲜度信息", false);
            }
            MetricRow previous = byKey.putIfAbsent(metric.key(), metric);
            if (previous != null && !previous.sameCounters(metric)) {
                return Decision.rejected("同一上游实例存在相互冲突的窗口证据，不能生成计划", false);
            }
        }

        if (byKey.values().stream().map(MetricRow::routeId).distinct().count() != 1) {
            return Decision.rejected("当前调查证据涉及多个路由，无法确定唯一处置范围", false);
        }
        Set<String> confirmedIds = Set.copyOf(h6.supportingEvidenceIds());
        Map<String, List<MetricRow>> groups = new LinkedHashMap<>();
        byKey.values().stream().filter(row -> row.requests() > 0)
                .forEach(row -> groups.computeIfAbsent(row.group(), ignored -> new ArrayList<>()).add(row));
        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<String, List<MetricRow>> entry : groups.entrySet()) {
            String group = entry.getKey();
            List<MetricRow> groupRows = entry.getValue();
            if (group.isBlank() || groupRows.stream().anyMatch(row -> row.requests() < 5 && row.errors() > 0)) {
                continue;
            }
            long requests = 0;
            long errors = 0;
            int failedHosts = 0;
            List<String> refs = new ArrayList<>();
            boolean arithmeticValid = true;
            for (MetricRow row : groupRows) {
                try {
                    requests = Math.addExact(requests, row.requests());
                    errors = Math.addExact(errors, row.errors());
                } catch (ArithmeticException ex) {
                    arithmeticValid = false;
                    break;
                }
                if (row.errors() > 0 && !confirmedIds.contains(row.evidenceId())) {
                    arithmeticValid = false;
                    break;
                }
                if (row.errors() >= 2 && (double) row.errors() / row.requests() >= MIN_GROUP_ERROR_RATE) {
                    failedHosts++;
                }
                refs.add(row.evidenceId());
            }
            if (!arithmeticValid || requests < MIN_GROUP_REQUESTS || errors < MIN_GROUP_5XX
                    || (double) errors / requests < MIN_GROUP_ERROR_RATE
                    || failedHosts * 100 < groupRows.size() * MIN_FAILED_HOSTS_PERCENT) {
                continue;
            }
            candidates.add(new Candidate(group, errors, requests, refs));
        }
        if (candidates.isEmpty()) {
            return Decision.rejected("证据未达到自动候选门槛：同一版本组需至少 20 个请求、5 个 5xx、错误率不低于 20%，"
                    + "且至少一半有流量实例重复出现错误；单次 5xx 不会触发停推建议", false);
        }
        if (candidates.size() != 1) {
            return Decision.rejected("多个版本组同时达到异常门槛，无法从现有证据唯一选择处置目标", false);
        }
        if (actions == null) {
            return Decision.rejected("证据达到候选门槛，但当前运行方式没有接入变更提议服务", true);
        }

        Candidate candidate = candidates.get(0);
        try {
            ActionProposal proposal = actions.proposeEvidenceGatedQuarantine(task.sessionId(), task.taskId(),
                    task.incidentId(), path, candidate.group(), candidate.evidenceRefs());
            if (!proposal.created()) {
                return Decision.rejected("证据达到候选门槛，但没有创建计划：" + proposal.reason(), true);
            }
            AgentAction action = proposal.action();
            return Decision.created(action, "已生成待人工审批的候选计划：将 " + action.targetLabel()
                    + " 的权重从 " + action.beforeWeight() + " 调整为 0；窗口内 " + candidate.errors() + "/"
                    + candidate.requests() + " 次上游请求返回 5xx。计划仅待审批，尚未执行。",
                    candidate.evidenceRefs());
        } catch (AgentAccess.Denied ex) {
            return Decision.rejected("证据达到候选门槛，但会话账号没有提案权限，未创建计划", true);
        } catch (RuntimeException ex) {
            log.warn("自动候选处置计划登记失败: task={}", task.taskId(), ex);
            return Decision.rejected("证据达到候选门槛，但计划登记失败；诊断结论已保留", true);
        }
    }

    private static MetricRow decode(Evidence item, InvestigationTask task, String path, long now) {
        var envelope = item.envelope();
        InvestigationContext context = envelope.context();
        ResourceTarget target = context == null ? null : context.target();
        if (context == null || !Objects.equals(task.taskId(), item.taskId())
                || !Objects.equals(task.taskId(), context.taskId())
                || !Objects.equals(task.incidentId(), context.incidentId()) || target == null
                || target.type() != TargetType.ROUTE || !path.equals(target.value())
                || envelope.qualityStatus() != EvidenceQuality.AVAILABLE || envelope.requestedRange() == null
                || !envelope.requestedRange().isUnspecified() || envelope.collectedAtMillis() <= 0
                || envelope.collectedAtMillis() > now || now - envelope.collectedAtMillis() > MAX_EVIDENCE_AGE_MILLIS
                || envelope.validUntilMillis() == null || envelope.validUntilMillis() < now
                || !Boolean.TRUE.equals(envelope.facts().get("complete"))) {
            return null;
        }
        Map<String, Object> facts = envelope.facts();
        String routeId = string(facts.get("routeId"));
        String hostPort = string(facts.get("hostPort"));
        String group = string(facts.get("group"));
        long requests = number(facts.get("windowRequests"));
        long errors = number(facts.get("status5xx"));
        long window = number(facts.get("windowSeconds"));
        if (routeId.isBlank() || hostPort.isBlank() || group.isBlank() || requests < 0 || errors < 0
                || errors > requests || window <= 0 || window > 300 || requests != envelope.sampleCount()) {
            return null;
        }
        return new MetricRow(item.evidenceId(), routeId, hostPort, group, requests, errors);
    }

    private static String string(Object value) {
        return value instanceof String text ? text : "";
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : -1;
    }

    private record MetricRow(String evidenceId, String routeId, String hostPort, String group,
                             long requests, long errors) {
        MetricKey key() { return new MetricKey(routeId, hostPort, group); }
        boolean sameCounters(MetricRow other) {
            return requests == other.requests && errors == other.errors && routeId.equals(other.routeId)
                    && hostPort.equals(other.hostPort) && group.equals(other.group);
        }
    }

    private record MetricKey(String routeId, String hostPort, String group) { }

    private record Candidate(String group, long errors, long requests, List<String> evidenceRefs) {
        Candidate {
            evidenceRefs = evidenceRefs.stream().distinct().sorted(Comparator.naturalOrder()).toList();
        }
    }

    public record Decision(AgentAction action, String detail, boolean evidenceQualified, List<String> evidenceRefs) {
        public Decision {
            detail = detail == null ? "" : detail;
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
        static Decision rejected(String reason, boolean evidenceQualified) {
            return new Decision(null, reason, evidenceQualified, List.of());
        }
        static Decision created(AgentAction action, String detail, List<String> evidenceRefs) {
            return new Decision(action, detail, true, evidenceRefs);
        }
        public boolean created() { return action != null; }
    }
}
