package com.rover.agent.core.planning;

import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.capability.CapabilityDescriptor;
import com.rover.agent.core.capability.CapabilityRegistry;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.TargetType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 计划校验：模型可以提建议，边界由代码决定。
 *
 * 校验逐条执行，任一条不满足的步骤直接被丢弃（而不是报错中断），这样即使模型胡言乱语，
 * 调查仍能按确定性部分继续：
 * <ol>
 *   <li>能力必须在注册表里 <b>已接入且只读</b>——未登记的能力名（含 shell、SQL 之类）一律拒绝；</li>
 *   <li>步骤目标不能越权：请求已确定目标时，步骤只能作用于该目标；</li>
 *   <li>目标类型必须被该能力支持；</li>
 *   <li>同一个能力在计划里只保留一次，且已处理过的能力不再重复规划；</li>
 *   <li>步骤数截断到 {@link PlanningLimits#maxPlanSteps()}。</li>
 * </ol>
 */
public final class PlanValidator {

    /** 候选故障原因最多展示条数：计划里的假设只是排查方向，不值得无限堆。 */
    private static final int MAX_HYPOTHESES = 6;

    private final CapabilityRegistry registry;
    private final PlanningLimits limits;

    public PlanValidator(CapabilityRegistry registry, PlanningLimits limits) {
        this.registry = registry == null ? CapabilityRegistry.standard() : registry;
        this.limits = limits == null ? PlanningLimits.defaults() : limits;
    }

    /**
     * 校验并规整一份计划。
     *
     * @param plan     待校验计划（可能来自模型）
     * @param request  本次规划请求：提供目标边界
     * @param settled  已处理过的能力（执行过或确定跳过），不再重复规划
     * @return 只包含可执行只读步骤的计划；无合法步骤时返回空计划
     */
    public InvestigationPlan validate(InvestigationPlan plan, PlanningRequest request,
                                      Set<AgentCapability> settled) {
        InvestigationPlan source = plan == null ? InvestigationPlan.empty() : plan;
        PlanningRequest context = request == null
                ? new PlanningRequest("", null, ResourceTarget.unknown(), "", List.of(), List.of()) : request;
        Set<AgentCapability> handled = settled == null ? Set.of() : settled;
        List<PlannedStep> steps = new ArrayList<>();
        Set<AgentCapability> seen = new LinkedHashSet<>();
        for (PlannedStep step : source.steps()) {
            if (steps.size() >= limits.maxPlanSteps()) {
                break;
            }
            AgentCapability capability = step.capability();
            if (!registry.selectable(capability) || handled.contains(capability) || !seen.add(capability)) {
                continue;
            }
            ResourceTarget target = normaliseTarget(step.target(), context);
            if (!supports(capability, target)) {
                continue;
            }
            steps.add(new PlannedStep(capability, step.reason(), target, step.required()));
        }
        return new InvestigationPlan(source.goal(), trimHypotheses(source.hypotheses()), steps);
    }

    /** 本次请求的边界目标：优先用已解析目标，其次是请求路径推导出的路由目标。 */
    public static ResourceTarget requestTarget(PlanningRequest request) {
        if (request == null) {
            return ResourceTarget.unknown();
        }
        ResourceTarget target = request.target();
        if (target != null && target.type() != TargetType.UNKNOWN && !target.value().isBlank()) {
            return target;
        }
        return request.path().isBlank() ? ResourceTarget.unknown() : ResourceTarget.route(request.path());
    }

    /**
     * 目标归一化：请求目标已确定时，步骤目标强制归位到请求目标。
     *
     * 这样模型不能借计划把调查范围扩大到别的服务或实例上——「只查用户问的那个对象」是由代码保证的。
     */
    private static ResourceTarget normaliseTarget(ResourceTarget stepTarget, PlanningRequest request) {
        ResourceTarget boundary = requestTarget(request);
        if (boundary.type() != TargetType.UNKNOWN && !boundary.value().isBlank()) {
            return boundary;
        }
        return stepTarget == null ? ResourceTarget.unknown() : stepTarget;
    }

    private boolean supports(AgentCapability capability, ResourceTarget target) {
        Optional<CapabilityDescriptor> descriptor = registry.descriptor(capability);
        return descriptor.map(item -> item.supports(target.type())).orElse(false);
    }

    private static List<String> trimHypotheses(List<String> hypotheses) {
        List<String> trimmed = new ArrayList<>();
        for (String item : hypotheses) {
            String text = item == null ? "" : item.trim();
            if (text.isBlank() || trimmed.contains(text)) {
                continue;
            }
            trimmed.add(text);
            if (trimmed.size() >= MAX_HYPOTHESES) {
                break;
            }
        }
        return List.copyOf(trimmed);
    }
}