package com.rover.agent.runtime;

import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.capability.CapabilityExecutor;
import com.rover.agent.core.capability.CapabilityResult;
import com.rover.agent.core.intent.QuerySubject;
import com.rover.agent.core.investigation.EvidenceNarrator;
import com.rover.agent.core.investigation.InvestigationRules;
import com.rover.agent.core.model.AgentStepType;
import com.rover.agent.core.model.Confidence;
import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.InvestigationReport;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.StepStatus;
import com.rover.agent.core.model.TargetType;
import com.rover.agent.core.snapshot.GatewayMetricSnapshot;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import com.rover.agent.core.snapshot.RouteUpstreamSnapshot;
import com.rover.agent.runtime.task.InvestigationTask;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 状态查询用例：回答「现在是多少 / 有几个 / 命中哪条路由」这类事实性问题。
 *
 * 它与故障调查的区别是刻意的：<b>只调用一个只读能力、不做假设判定、不进入模型解读</b>。
 * 「网关 QPS 多少」不需要读路由、实例与追踪，也不需要让模型解释；把这类问题当故障调查跑一遍
 * 既慢又会让用户以为系统出了问题。回答由取回的结构化事实直接拼出，措辞固定、可复现。
 *
 * 取数仍然只经过 {@link CapabilityExecutor}：模型、提示词与 HTTP 细节都不参与，
 * 能力边界（只读、可用性）与调查路径完全一致。
 */
public final class QueryStateService {

    private static final Logger log = LoggerFactory.getLogger(QueryStateService.class);

    private static final String STEP_ANSWER = "回答";
    private static final String NO_SUBJECT =
            "未能从问题中识别出要查询的状态口径（实例 / 指标 / 路由 / 配置 / 事件），请说明要查哪一类事实。";
    private static final String NO_SUBJECT_NOTE =
            "状态查询需要明确的口径：实例健康、Gateway 指标、路由配置、生效配置或注册事件。";

    private final CapabilityExecutor executor;

    public QueryStateService(CapabilityExecutor executor) {
        this.executor = executor;
    }

    /**
     * 执行一次状态查询：按口径选一个只读能力，取数后直接给答案。
     *
     * @param subject 查询口径；{@link QuerySubject#NONE} 时如实说明无法确定口径，不猜能力
     */
    public void run(InvestigationTask task, QuerySubject subject) {
        task.start();
        try {
            if (subject == null || subject == QuerySubject.NONE) {
                task.step(AgentStepType.ANSWER, STEP_ANSWER, StepStatus.COMPLETED, NO_SUBJECT);
                finish(task, NO_SUBJECT, Confidence.LOW, List.of(), List.of(NO_SUBJECT_NOTE));
                return;
            }
            AgentCapability capability = capabilityOf(subject);
            // 能力使用情况随快照暴露：用户能看到这次回答只读了一个能力，而不是跑了一整轮调查。
            task.reportCapabilityExecuted(capability);
            CapabilityResult result = executor.execute(capability, task.target(), task.path(), task.taskId());
            if (result.evidence().isEmpty()) {
                String limitation = firstLimitation(result);
                task.step(AgentStepType.ANSWER, STEP_ANSWER, StepStatus.FAILED, limitation);
                finish(task, limitation, Confidence.LOW, result.evidence(), result.limitations());
                return;
            }
            String answer = answerOf(subject, task, result);
            task.step(AgentStepType.ANSWER, STEP_ANSWER, StepStatus.COMPLETED, answer);
            finish(task, answer, Confidence.HIGH, result.evidence(), result.limitations());
        } catch (Exception ex) {
            log.error("Agent 状态查询异常", ex);
            task.fail("状态查询执行失败");
        }
    }

    /**
     * 查询口径到只读能力的映射：口径决定取哪一类事实，不做任何推断。
     *
     * {@link QuerySubject#NONE} 在 {@link #run} 入口就已被拦下并如实答复，走到这里说明调用方绕过入口，属于编程错误。
     */
    private static AgentCapability capabilityOf(QuerySubject subject) {
        return switch (subject) {
            case METRIC -> AgentCapability.GATEWAY_METRICS_QUERY;
            case INSTANCE -> AgentCapability.INSTANCE_QUERY;
            case ROUTE -> AgentCapability.ROUTE_QUERY;
            case CONFIG -> AgentCapability.CONFIG_READ;
            case EVENT -> AgentCapability.EVENT_QUERY;
            case NONE -> throw new IllegalStateException("状态查询口径为 NONE 时不应取数");
        };
    }

    private static String answerOf(QuerySubject subject, InvestigationTask task, CapabilityResult result) {
        return switch (subject) {
            case METRIC -> metricAnswer(result);
            case INSTANCE -> instanceAnswer(task.target(), result);
            case ROUTE -> routeAnswer(task.path(), result);
            case CONFIG, EVENT -> joinEvidence(result);
            case NONE -> throw new IllegalStateException("状态查询口径为 NONE 时不应生成回答");
        };
    }

    /**
     * 指标回答：请求数、折算速率、5xx 与全局拒绝累计一并给出，并保留「累计值不能归因」的口径提醒。
     *
     * 全局计数之外再补一句按上游实例的归因：全局 5xx 说明「有没有异常」，实例维度才说明「是哪台」。
     */
    private static String metricAnswer(CapabilityResult result) {
        GatewayMetricSnapshot metric = result.metric();
        if (metric == null) {
            return fact(result);
        }
        double perSecond = metric.windowRequests() / (double) CapabilityExecutor.METRIC_WINDOW_SECONDS;
        return "Gateway 最近 " + CapabilityExecutor.METRIC_WINDOW_SECONDS + " 秒窗口内请求数 "
                + metric.windowRequests() + " 次（平均约 " + oneDecimal(perSecond) + " 次/秒）；其中 5xx "
                + metric.status5xx() + " 次；全局无上游拒绝累计 " + metric.noUpstreamRejects()
                + " 次（累计值，不能单独归因到某条路径）。" + upstreamClause(result.routeUpstreams());
    }

    /**
     * 按上游实例的窗口观测补充归因：只有样本量达标的实例才会被点名。
     *
     * 样本不足的实例一律不点名——「1 次请求里 1 次 5xx」不能当成异常实例，这种情况如实说样本不足。
     */
    private static String upstreamClause(List<RouteUpstreamSnapshot> rows) {
        if (rows == null) {
            return "";
        }
        if (rows.isEmpty()) {
            return " 按上游实例的指标：最近窗口内没有转发记录，无法归因到具体实例，也不能据此判定异常已恢复。";
        }
        List<RouteUpstreamSnapshot> failing = rows.stream()
                .filter(item -> item != null && item.windowRequests() >= InvestigationRules.MIN_INSTANCE_SAMPLE
                        && item.status5xx() > 0)
                .toList();
        if (!failing.isEmpty()) {
            return " 其中 " + failing.stream().map(EvidenceNarrator::upstreamFact)
                    .collect(Collectors.joining("、")) + " 返回过 5xx。";
        }
        boolean lowSample = rows.stream().anyMatch(item -> item != null
                && item.windowRequests() < InvestigationRules.MIN_INSTANCE_SAMPLE && item.status5xx() > 0);
        if (lowSample) {
            return " 有上游实例返回过 5xx，但窗口请求数不足 " + InvestigationRules.MIN_INSTANCE_SAMPLE
                    + " 次，样本不足，无法归因到具体实例。";
        }
        return "";
    }

    /** 配置 / 事件类回答：一次能力可能产出多条证据（按组件拆分），逐条如实拼接。 */
    private static String joinEvidence(CapabilityResult result) {
        if (result.evidence().isEmpty()) {
            return firstLimitation(result);
        }
        return result.evidence().stream().map(Evidence::summary).collect(Collectors.joining("；"));
    }

    /** 实例回答：按目标服务统计健康实例数；目标不是服务时退回注册表整体口径的证据文本。 */
    private static String instanceAnswer(ResourceTarget target, CapabilityResult result) {
        List<InstanceSnapshot> instances = result.instances() == null ? List.of() : result.instances();
        if (target.type() != TargetType.SERVICE || target.value().isBlank()) {
            return fact(result);
        }
        long healthy = EvidenceNarrator.healthyCount(instances, target.value(), "");
        long registered = instances.stream()
                .filter(item -> target.value().equals(text(item.serviceName())))
                .count();
        return "服务 " + target.value() + " 当前健康实例 " + healthy + " 个（该服务注册实例共 " + registered
                + " 个；按全部分组统计，注册表共 " + instances.size() + " 个实例）。";
    }

    /** 路由回答：命中则给出路由事实，未命中或数据不可用都如实说明。 */
    private static String routeAnswer(String path, CapabilityResult result) {
        String where = path == null || path.isBlank() ? "该路径" : path;
        if (!result.routeRead()) {
            return fact(result);
        }
        RouteSnapshot route = result.route();
        if (route == null) {
            return "当前路由表没有匹配 " + where + " 的路由配置。";
        }
        return "请求路径 " + where + " 命中的路由：" + fact(result);
    }

    private static String fact(CapabilityResult result) {
        return result.evidence().isEmpty() ? firstLimitation(result) : result.evidence().get(0).summary();
    }

    private static String firstLimitation(CapabilityResult result) {
        return result.limitations().isEmpty() ? "只读数据不可用，本次没有取到事实。" : result.limitations().get(0);
    }

    private static void finish(InvestigationTask task, String answer, Confidence confidence,
                               List<Evidence> evidence, List<String> limitations) {
        task.complete(new InvestigationReport(answer, confidence, evidence, limitations, List.of(), null));
    }

    private static String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}