package com.rover.agent.runtime;

import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.capability.CapabilityExecutor;
import com.rover.agent.core.capability.CapabilityResult;
import com.rover.agent.core.intent.QuerySubject;
import com.rover.agent.core.investigation.EvidenceNarrator;
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
import com.rover.agent.runtime.task.InvestigationTask;
import java.util.List;
import java.util.Locale;
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
            "未能从问题中识别出要查询的状态口径（实例 / 指标 / 路由），请说明要查哪一类事实。";
    private static final String NO_SUBJECT_NOTE = "状态查询需要明确的口径：实例健康、Gateway 指标或路由配置。";

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

    /** 查询口径到只读能力的映射：口径决定取哪一类事实，不做任何推断。 */
    private static AgentCapability capabilityOf(QuerySubject subject) {
        return switch (subject) {
            case METRIC -> AgentCapability.GATEWAY_METRICS_QUERY;
            case INSTANCE -> AgentCapability.INSTANCE_QUERY;
            case ROUTE -> AgentCapability.ROUTE_QUERY;
            case NONE -> AgentCapability.ROUTE_QUERY;
        };
    }

    private static String answerOf(QuerySubject subject, InvestigationTask task, CapabilityResult result) {
        return switch (subject) {
            case METRIC -> metricAnswer(result);
            case INSTANCE -> instanceAnswer(task.target(), result);
            case ROUTE -> routeAnswer(task.path(), result);
            case NONE -> fact(result);
        };
    }

    /** 指标回答：请求数、折算速率、5xx 与全局拒绝累计一并给出，并保留「累计值不能归因」的口径提醒。 */
    private static String metricAnswer(CapabilityResult result) {
        GatewayMetricSnapshot metric = result.metric();
        if (metric == null) {
            return fact(result);
        }
        double perSecond = metric.windowRequests() / (double) CapabilityExecutor.METRIC_WINDOW_SECONDS;
        return "Gateway 最近 " + CapabilityExecutor.METRIC_WINDOW_SECONDS + " 秒窗口内请求数 "
                + metric.windowRequests() + " 次（平均约 " + oneDecimal(perSecond) + " 次/秒）；其中 5xx "
                + metric.status5xx() + " 次；全局无上游拒绝累计 " + metric.noUpstreamRejects()
                + " 次（累计值，不能单独归因到某条路径）。";
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