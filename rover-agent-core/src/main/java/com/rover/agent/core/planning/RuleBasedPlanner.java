package com.rover.agent.core.planning;

import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.capability.CapabilityRegistry;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.TargetType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 确定性规划器：不依赖模型也能产出计划，是无模型环境与模型失败时的兜底。
 *
 * 计划内容与既有固定调查链保持一致（路由 → 实例 → 指标 → 追踪），因此「模型不可用」只是少了解读，
 * 不会让故障调查退化。实例步骤在该能力对本目标无判定价值时（路由未知、静态上游、非 Nameserver）
 * 由执行侧跳过，跳过同样计入已处理，不会被下一轮重新规划。
 */
public final class RuleBasedPlanner implements InvestigationPlanner {

    /** 计划目标文本长度上限：用户问题可能很长，计划头只保留可读的一段。 */
    private static final int MAX_GOAL_LENGTH = 120;

    private static final String REASON_ROUTE = "确认请求路径命中的路由与目标服务";
    private static final String REASON_INSTANCE = "核对目标服务的注册实例与健康实例数";
    private static final String REASON_METRIC = "确认 Gateway 最近窗口的流量与拒绝计数";
    private static final String REASON_TRACE = "查看该路径最近的请求结果";
    private static final String REASON_CONFIG = "查看当前生效的限流、熔断与超时配置";
    private static final String REASON_EVENT = "查看注册中心最近的实例上下线经过";

    private final CapabilityRegistry registry;

    public RuleBasedPlanner(CapabilityRegistry registry) {
        this.registry = registry == null ? CapabilityRegistry.standard() : registry;
    }

    @Override
    public InvestigationPlan plan(PlanningRequest request) {
        PlanningRequest context = request == null
                ? new PlanningRequest("", ResourceTarget.unknown(), "", List.of(), List.of()) : request;
        ResourceTarget target = PlanValidator.requestTarget(context);
        boolean hasPath = !context.path().isBlank();
        if (!hasPath && target.type() == TargetType.UNKNOWN) {
            return InvestigationPlan.empty();
        }
        List<PlannedStep> steps = new ArrayList<>();
        if (hasPath) {
            add(steps, AgentCapability.ROUTE_QUERY, REASON_ROUTE, target, true);
            add(steps, AgentCapability.INSTANCE_QUERY, REASON_INSTANCE, target, true);
            add(steps, AgentCapability.GATEWAY_METRICS_QUERY, REASON_METRIC, target, false);
            add(steps, AgentCapability.TRACE_QUERY, REASON_TRACE, target, true);
        } else {
            // 没有请求路径时不规划路由与追踪：这两类能力都以路径为取数口径，强行执行只会产出噪声。
            add(steps, AgentCapability.INSTANCE_QUERY, REASON_INSTANCE, target, true);
        }
        addQuestionDrivenSteps(steps, context, target);
        return new InvestigationPlan(goal(context, target, hasPath), hypotheses(hasPath), steps);
    }

    /**
     * 问题本身点到配置或事件时，才追加对应的可选步骤。
     *
     * 这两类事实对常见故障调查没有普遍判定价值，因此不做成必查项：每次都读会凭空增加两跳只读调用，
     * 也会让证据里塞进与问题无关的内容。是否点到由 {@link InvestigationCueDetector} 判断，
     * 这里是唯一的调用方，不维护第二份关键词。
     *
     * 两个方向各自成立、互不排斥：一句话同时提到配置与变更（「限流阈值是不是改了」）时两条都追加。
     * 提示只说明「这个方向值得看一眼」，不构成「该查哪一类」的裁决——真的根因不该由先命中的关键词决定。
     */
    private void addQuestionDrivenSteps(List<PlannedStep> steps, PlanningRequest request, ResourceTarget target) {
        String question = request.question();
        if (InvestigationCueDetector.mentionsConfig(question)) {
            add(steps, AgentCapability.CONFIG_READ, REASON_CONFIG, target, false);
        }
        if (InvestigationCueDetector.mentionsEvent(question)) {
            add(steps, AgentCapability.EVENT_QUERY, REASON_EVENT, target, false);
        }
    }

    @Override
    public PlanningDecision evaluate(PlanningRequest request, PlanProgress progress) {
        PlanningRequest context = request == null
                ? new PlanningRequest("", ResourceTarget.unknown(), "", List.of(), List.of()) : request;
        PlanProgress state = progress == null ? PlanProgress.empty() : progress;
        Set<AgentCapability> missing = new LinkedHashSet<>(requiredCapabilities(context));
        missing.removeAll(state.settled());
        if (missing.isEmpty()) {
            return PlanningDecision.finish("关键事实已采集完毕，进入结论综合");
        }
        if (missing.stream().noneMatch(registry::selectable)) {
            return PlanningDecision.finish("还缺少的能力尚未接入，无法继续采集");
        }
        return PlanningDecision.continuePlanning("还缺少 " + join(missing) + " 的事实，需要再执行一轮只读查询");
    }

    /** 本目标下必查的能力：缺任何一项都会影响结论的完整性。 */
    public Set<AgentCapability> requiredCapabilities(PlanningRequest request) {
        PlanningRequest context = request == null
                ? new PlanningRequest("", ResourceTarget.unknown(), "", List.of(), List.of()) : request;
        Set<AgentCapability> required = new LinkedHashSet<>();
        if (!context.path().isBlank()) {
            required.add(AgentCapability.ROUTE_QUERY);
            required.add(AgentCapability.INSTANCE_QUERY);
            required.add(AgentCapability.TRACE_QUERY);
        } else if (PlanValidator.requestTarget(context).type() != TargetType.UNKNOWN) {
            required.add(AgentCapability.INSTANCE_QUERY);
        }
        return required;
    }

    private void add(List<PlannedStep> steps, AgentCapability capability, String reason, ResourceTarget target,
                     boolean required) {
        if (registry.selectable(capability)) {
            steps.add(required ? PlannedStep.required(capability, reason, target)
                    : PlannedStep.optional(capability, reason, target));
        }
    }

    private static List<String> hypotheses(boolean hasPath) {
        List<String> hypotheses = new ArrayList<>();
        if (hasPath) {
            hypotheses.add("该路径未命中任何路由");
        }
        hypotheses.add("目标服务没有匹配的注册实例");
        hypotheses.add("匹配实例均不健康");
        if (hasPath) {
            hypotheses.add("该路由的某个上游实例返回了 5xx");
            hypotheses.add("该路径近期返回 503");
        }
        return List.copyOf(hypotheses);
    }

    private static String goal(PlanningRequest request, ResourceTarget target, boolean hasPath) {
        String label = hasPath ? request.path() : target.value();
        String goal = "定位 " + label + " 的问题原因";
        if (request.question().isBlank()) {
            return goal;
        }
        String question = request.question();
        if (question.length() > MAX_GOAL_LENGTH) {
            question = question.substring(0, MAX_GOAL_LENGTH) + "…";
        }
        return goal + "（用户问题：" + question + "）";
    }

    private static String join(Set<AgentCapability> capabilities) {
        StringBuilder text = new StringBuilder();
        for (AgentCapability capability : capabilities) {
            text.append(text.length() == 0 ? "" : "、").append(capability);
        }
        return text.toString();
    }
}