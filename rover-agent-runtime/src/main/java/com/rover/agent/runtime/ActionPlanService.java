package com.rover.agent.runtime;

import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.capability.CapabilityExecutor;
import com.rover.agent.core.capability.CapabilityResult;
import com.rover.agent.core.model.ActionPlan;
import com.rover.agent.core.model.ActionType;
import com.rover.agent.core.model.AgentStepType;
import com.rover.agent.core.model.Confidence;
import com.rover.agent.core.model.IntentDecision;
import com.rover.agent.core.model.InvestigationReport;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.RiskLevel;
import com.rover.agent.core.model.StepStatus;
import com.rover.agent.core.model.TargetType;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.runtime.task.InvestigationTask;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 处置计划用例：把「把 order-03 摘掉」这类请求整理成一份可人工审核的建议，本阶段一律不执行。
 *
 * 三件事是刻意分开的：
 * <ol>
 *   <li><b>权限与执行边界由 Java 决定</b>：{@link ActionPlan} 的 {@code executable} 恒为 false，
 *       本服务不持有任何写入口，连"尝试执行"的代码路径都不存在；</li>
 *   <li><b>预检是只读的</b>：按动作类型选一个只读能力读当前状态（实例类动作读实例、路由类动作读路由），
 *       预检证据随计划一起留给人工核对；</li>
 *   <li><b>动作语义未识别时不假装</b>：{@link ActionType#UNKNOWN} 仍然出计划，但风险按 HIGH 处理，
 *       并在计划里写明「未识别为本版本支持的处置动作，需人工确认语义」。</li>
 * </ol>
 *
 * 目标解析不出来也照样出计划：处置请求的产物是计划本身，不是「目标必须存在」的判定；
 * 未解析出的目标以用户原文保留在计划里，人工执行前自己去核对。
 */
public final class ActionPlanService {

    private static final Logger log = LoggerFactory.getLogger(ActionPlanService.class);

    private static final String STEP_ACTION_PLANNING = "生成处置计划";

    private final CapabilityExecutor executor;

    public ActionPlanService(CapabilityExecutor executor) {
        this.executor = executor;
    }

    /** 执行一次处置计划生成：只读预检 → 组装计划 → 落任务。 */
    public void run(InvestigationTask task, IntentDecision decision) {
        task.start();
        try {
            IntentDecision intent = decision;
            ActionType action = intent == null ? ActionType.UNKNOWN : intent.requestedAction();
            String targetDescription = intent == null || intent.targetHint().isBlank()
                    ? task.target().value() : intent.targetHint();

            AgentCapability precheck = precheckCapability(action);
            // 能力使用情况随快照暴露：用户能看到这次只读预检用了哪个能力，且没有别的调用。
            task.reportCapabilityExecuted(precheck);
            CapabilityResult current = executor.execute(precheck, task.target(), task.path(), task.taskId());

            ActionPlan plan = assemble(action, targetDescription, task.target(), current, intent);
            task.attachActionPlan(plan);
            task.step(AgentStepType.ACTION_PLANNING, STEP_ACTION_PLANNING, StepStatus.COMPLETED, plan.summary());
            task.complete(new InvestigationReport(render(plan), plan.currentState().isBlank()
                    ? Confidence.LOW : Confidence.MEDIUM, current.evidence(), limitations(plan, current),
                    List.of(), null));
        } catch (Exception ex) {
            log.error("Agent 处置计划生成异常", ex);
            task.fail("处置计划生成失败");
        }
    }

    /** 预检能力：实例类动作读实例快照，其余（含未识别动作）读路由事实。 */
    private static AgentCapability precheckCapability(ActionType action) {
        return action != null && action.targetsInstance()
                ? AgentCapability.INSTANCE_QUERY : AgentCapability.ROUTE_QUERY;
    }

    private static ActionPlan assemble(ActionType action, String targetDescription, ResourceTarget target,
                                       CapabilityResult current, IntentDecision intent) {
        InstanceSnapshot instance = findInstance(target, current.instances());
        return new ActionPlan(action, targetDescription, target, reasonOf(action, intent),
                riskOf(action, target, instance, current),
                stateOf(action, target, instance, current),
                desiredOf(action),
                impactOf(action, instance, current),
                verifyOf(action),
                rollbackOf(action),
                false, null);
    }

    /** 建议原因：写明动作来自哪句请求，未识别动作时如实说明。 */
    private static String reasonOf(ActionType action, IntentDecision intent) {
        String judgement = intent == null ? "" : intent.reason();
        if (action == ActionType.UNKNOWN) {
            return "用户请求了一次处置操作，但" + (judgement.isBlank() ? "未能识别具体动作" : judgement)
                    + "；未识别为本版本支持的处置动作，计划仅供人工确认。";
        }
        return "用户请求执行" + action.label() + (judgement.isBlank() ? "" : "（" + judgement + "）") + "。";
    }

    /**
     * 风险分级：以「执行后可用容量的变化」为准，而不是动作名称的字面轻重。
     *
     * 摘除实例时若预检显示该服务只剩这一个健康实例，升级为 HIGH——那不是「减一台」，
     * 而是把服务摘空。
     */
    private static RiskLevel riskOf(ActionType action, ResourceTarget target, InstanceSnapshot instance,
                                    CapabilityResult current) {
        if (action == ActionType.RESTORE_INSTANCE) {
            return RiskLevel.LOW;
        }
        if (action == ActionType.DRAIN_INSTANCE) {
            if (instance != null && instance.healthy() && healthyPeers(instance, current) <= 1) {
                return RiskLevel.HIGH;
            }
            return RiskLevel.MEDIUM;
        }
        if (action == ActionType.UPDATE_ROUTE_TIMEOUT || action == ActionType.UPDATE_RATE_LIMIT) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.HIGH;
    }

    /** 同服务（同分组）健康实例数：用于判断摘除后是否还有容量。 */
    private static long healthyPeers(InstanceSnapshot instance, CapabilityResult current) {
        List<InstanceSnapshot> rows = current.instances() == null ? List.of() : current.instances();
        String service = text(instance.serviceName());
        String group = text(instance.group());
        return rows.stream()
                .filter(item -> service.equals(text(item.serviceName())))
                .filter(item -> group.isEmpty() || group.equals(text(item.group())))
                .filter(InstanceSnapshot::healthy)
                .count();
    }

    /** 当前状态：优先给结构化事实（命中实例 / 命中的路由），取不到时退回证据首条或判断边界。 */
    private static String stateOf(ActionType action, ResourceTarget target, InstanceSnapshot instance,
                                  CapabilityResult current) {
        if (action.targetsInstance()) {
            if (instance == null) {
                return fact(current) + "；未在注册表中定位到目标实例 " + targetLabel(target)
                        + "，执行前需人工核对实例地址。";
            }
            return "实例 " + text(instance.serviceName()) + "/" + groupLabel(instance.group()) + "@"
                    + text(instance.host()) + ":" + instance.port() + " 当前"
                    + (instance.healthy() ? "健康" : "不健康") + "；" + fact(current);
        }
        return fact(current);
    }

    private static String desiredOf(ActionType action) {
        return switch (action) {
            case DRAIN_INSTANCE -> "该实例不再接收新流量，请求全部由同服务的其他健康实例承接。";
            case RESTORE_INSTANCE -> "该实例重新加入负载均衡并开始接收流量。";
            case UPDATE_ROUTE_TIMEOUT -> "该路由对上游的调用超时时间调整为请求中指定的值。";
            case UPDATE_RATE_LIMIT -> "该路由的限流阈值调整为请求中指定的值。";
            case UNKNOWN -> "按用户意图完成处置（动作未识别，需人工确认后再定义目标状态）。";
        };
    }

    private static String impactOf(ActionType action, InstanceSnapshot instance, CapabilityResult current) {
        if (action == ActionType.DRAIN_INSTANCE) {
            long peers = instance == null ? 0 : healthyPeers(instance, current);
            if (instance != null && peers <= 1) {
                return "按当前注册数据，摘除后该服务将没有健康实例，上层调用可能直接失败；属于高风险操作，"
                        + "执行前必须确认有替代容量。";
            }
            return "该服务可用实例减少 1 个（按当前注册数据剩余健康实例 " + peers + " 个），单实例容量下降，"
                    + "总吞吐由剩余实例分担。";
        }
        if (action == ActionType.RESTORE_INSTANCE) {
            return "该服务可用实例增加 1 个，总容量上升，无容量风险。";
        }
        if (action == ActionType.UPDATE_ROUTE_TIMEOUT || action == ActionType.UPDATE_RATE_LIMIT) {
            return "该路由上匹配请求的行为会立即变化（超时判定或限流放行口径），影响范围限于该路由。";
        }
        return "影响范围未知：动作语义未识别，无法评估执行影响。";
    }

    private static String verifyOf(ActionType action) {
        return switch (action) {
            case DRAIN_INSTANCE -> "执行后查询该服务实例健康数与目标路径追踪，确认流量已转移到其他实例且无新增失败。";
            case RESTORE_INSTANCE -> "执行后查询该服务实例健康数，确认实例重新出现在健康列表中。";
            case UPDATE_ROUTE_TIMEOUT -> "执行后读取路由配置确认取值，并观察该路径的延迟与超时比例。";
            case UPDATE_RATE_LIMIT -> "执行后读取路由配置确认阈值，并观察该路径的拒绝计数与错误率。";
            case UNKNOWN -> "动作未识别，暂无标准验证方式；执行前先人工确认动作语义与验证口径。";
        };
    }

    private static String rollbackOf(ActionType action) {
        return switch (action) {
            case DRAIN_INSTANCE -> "把该实例重新上线（RESTORE_INSTANCE），待其通过健康检查后恢复流量。";
            case RESTORE_INSTANCE -> "必要时再次摘除该实例（DRAIN_INSTANCE）。";
            case UPDATE_ROUTE_TIMEOUT, UPDATE_RATE_LIMIT -> "把该路由配置改回预检读到的原值（原值见本次预检证据）。";
            case UNKNOWN -> "动作未识别，无法给出标准回滚方式；执行前先确定变更内容与对应的回退取值。";
        };
    }

    /** 判断边界：数据不可用的说明与「不执行」的硬约束都要随报告带出。 */
    private static List<String> limitations(ActionPlan plan, CapabilityResult current) {
        List<String> notes = new ArrayList<>(current.limitations());
        notes.add(ActionPlan.NOT_EXECUTABLE_REASON);
        if (plan.actionType() == ActionType.UNKNOWN) {
            notes.add("处置动作未识别为本版本支持的类型，计划中的风险与影响评估仅供参考。");
        }
        return List.copyOf(notes);
    }

    /** 计划全文：结论区展示用，逐项列清人工执行前需要核对的内容。 */
    private static String render(ActionPlan plan) {
        return "已生成受控处置计划（不执行）：" + plan.actionType().label() + "\n"
                + "- 目标：" + plan.targetLabel() + "\n"
                + "- 风险级别：" + plan.riskLevel() + "\n"
                + "- 当前状态：" + plan.currentState() + "\n"
                + "- 目标状态：" + plan.desiredState() + "\n"
                + "- 预期影响：" + plan.expectedImpact() + "\n"
                + "- 验证方式：" + plan.verificationPlan() + "\n"
                + "- 回滚方式：" + plan.rollbackPlan() + "\n"
                + "- " + plan.blockedReason();
    }

    /** 在实例快照里定位目标实例：目标为实例地址（host:port）时精确匹配。 */
    private static InstanceSnapshot findInstance(ResourceTarget target, List<InstanceSnapshot> instances) {
        if (target == null || target.type() != TargetType.INSTANCE || instances == null) {
            return null;
        }
        String value = text(target.value());
        return instances.stream()
                .filter(item -> (text(item.host()) + ":" + item.port()).equals(value))
                .findFirst()
                .orElse(null);
    }

    private static String fact(CapabilityResult result) {
        return result.evidence().isEmpty()
                ? (result.limitations().isEmpty() ? "只读数据不可用，本次没有取到预检事实。" : result.limitations().get(0))
                : result.evidence().get(0).summary();
    }

    private static String targetLabel(ResourceTarget target) {
        return target == null || target.value().isBlank() ? "未指定" : target.value();
    }

    private static String groupLabel(String group) {
        return text(group).isEmpty() ? "默认组" : text(group);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}