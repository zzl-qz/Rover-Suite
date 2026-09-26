package com.rover.agent.core.capability;

import com.rover.agent.core.model.AgentStepType;
import com.rover.agent.core.model.TargetType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 能力注册表：Agent 能做什么的唯一声明处。
 *
 * 两件事都由这里保证：
 * <ol>
 *   <li>Planner 只能选择注册表里 <b>已接入且只读</b> 的能力，凭空生成的能力名会被校验丢弃；</li>
 *   <li>「你能做什么」这类问题的回答来自同一份注册表，模型不会声称系统具备未实现的能力。</li>
 * </ol>
 *
 * 注册表是纯数据，不含执行逻辑：执行映射在 {@link CapabilityExecutor}。
 */
public final class CapabilityRegistry {

    private static final String SOURCE_ROUTES = "Gateway 路由表";
    private static final String SOURCE_INSTANCES = "Nameserver 实例注册表";
    private static final String SOURCE_METRICS = "Gateway 实时指标";
    private static final String SOURCE_TRACES = "Gateway 抽样追踪";
    private static final String SOURCE_CONFIGS = "Gateway / Nameserver 生效配置";
    private static final String SOURCE_EVENTS = "Nameserver 事件流";

    private final List<CapabilityDescriptor> descriptors;

    public CapabilityRegistry(List<CapabilityDescriptor> descriptors) {
        this.descriptors = descriptors == null ? List.of() : List.copyOf(descriptors);
    }

    /**
     * 本阶段的标准注册表：六个已接入的只读能力。
     *
     * 未接入数据适配器的能力照样登记（用户问「有哪些能力」时看得到边界），但不可被 Planner 选择。
     * 目前六个能力全部接入，因此没有「已登记但未开放」项——一旦某个适配器被移除，把对应能力改为
     * {@code available=false} 即可，Planner 与能力清单会自动跟着收口。
     */
    public static CapabilityRegistry standard() {
        return new CapabilityRegistry(List.of(
                new CapabilityDescriptor(AgentCapability.ROUTE_QUERY, "路由查询", "查询 Gateway 路由及目标服务",
                        CapabilityRisk.READ_ONLY,
                        List.of(TargetType.ROUTE, TargetType.SERVICE, TargetType.INSTANCE),
                        true, AgentStepType.ROUTE_INVESTIGATION, "读取路由"),
                new CapabilityDescriptor(AgentCapability.INSTANCE_QUERY, "实例查询", "查询注册中心实例与目标服务健康实例数",
                        CapabilityRisk.READ_ONLY,
                        List.of(TargetType.ROUTE, TargetType.SERVICE, TargetType.INSTANCE),
                        true, AgentStepType.INSTANCE_INVESTIGATION, "读取实例"),
                new CapabilityDescriptor(AgentCapability.GATEWAY_METRICS_QUERY, "网关指标查询",
                        "查询 Gateway 全局流量与拒绝计数，以及路由各上游实例的窗口请求数、状态码与延迟",
                        CapabilityRisk.READ_ONLY,
                        List.of(TargetType.UNKNOWN, TargetType.ROUTE, TargetType.SERVICE, TargetType.INSTANCE),
                        true, AgentStepType.METRIC_INVESTIGATION, "读取指标"),
                new CapabilityDescriptor(AgentCapability.TRACE_QUERY, "追踪查询", "查询指定请求路径的抽样追踪",
                        CapabilityRisk.READ_ONLY,
                        List.of(TargetType.ROUTE, TargetType.SERVICE, TargetType.INSTANCE),
                        true, AgentStepType.TRACE_INVESTIGATION, "读取追踪"),
                new CapabilityDescriptor(AgentCapability.CONFIG_READ, "配置读取",
                        "读取 Gateway 与 Nameserver 当前生效配置（限流、熔断、超时、采样率等）",
                        CapabilityRisk.READ_ONLY,
                        List.of(TargetType.UNKNOWN, TargetType.ROUTE, TargetType.SERVICE, TargetType.INSTANCE),
                        true, AgentStepType.CONFIG_INVESTIGATION, "读取配置"),
                new CapabilityDescriptor(AgentCapability.EVENT_QUERY, "事件查询",
                        "查询注册中心最近事件（注册、注销、剔除、标记不健康、推送）",
                        CapabilityRisk.READ_ONLY,
                        List.of(TargetType.UNKNOWN, TargetType.ROUTE, TargetType.SERVICE, TargetType.INSTANCE),
                        true, AgentStepType.EVENT_INVESTIGATION, "读取事件")));
    }

    /** 全部已登记能力（含未开放的）。 */
    public List<CapabilityDescriptor> all() {
        return descriptors;
    }

    /** 当前已接入且只读、可被 Planner 选择的能力。 */
    public List<CapabilityDescriptor> selectable() {
        return descriptors.stream().filter(CapabilityDescriptor::selectable).toList();
    }

    /** 某个能力的注册描述；未登记时为空。 */
    public Optional<CapabilityDescriptor> descriptor(AgentCapability capability) {
        return descriptors.stream().filter(item -> item.id() == capability).findFirst();
    }

    /** 该能力是否可被 Planner 选择。 */
    public boolean selectable(AgentCapability capability) {
        return descriptor(capability).map(CapabilityDescriptor::selectable).orElse(false);
    }

    /** 可被选择的能力集合。 */
    public Set<AgentCapability> selectableCapabilities() {
        Set<AgentCapability> selectable = new LinkedHashSet<>();
        selectable().forEach(item -> selectable.add(item.id()));
        return selectable;
    }

    /**
     * 能力清单文本：由注册表生成，而不是由模型自由发挥。
     *
     * 已开放的按能力逐条给出说明与风险级别；未开放的单独列出并说明原因，
     * 末尾补一句边界声明——处置类请求只出计划，不执行。
     */
    public String describe() {
        StringBuilder text = new StringBuilder("Rover 当前可用的只读能力（Agent 只能选择这些能力）：");
        for (CapabilityDescriptor item : descriptors) {
            if (!item.available()) {
                continue;
            }
            text.append("\n- ").append(item.id()).append("（").append(item.name()).append("）：")
                    .append(item.description()).append("；风险级别 ").append(item.risk()).append("。");
        }
        List<CapabilityDescriptor> pending = descriptors.stream().filter(item -> !item.available()).toList();
        if (!pending.isEmpty()) {
            text.append("\n尚未开放的能力（未接入数据适配器，Agent 不会调用）：");
            for (CapabilityDescriptor item : pending) {
                text.append("\n- ").append(item.id()).append("（").append(item.name()).append("）");
            }
        }
        text.append("\n处置类请求只生成不可执行的处置计划；当前版本不执行任何写操作。");
        return text.toString();
    }

    /**
     * 自我介绍 + 能力清单：识别不出意图、或用户直接问「你是谁」时的统一回答。
     *
     * 与 {@link #describe()} 同源——先讲清身份与可以直接问什么，再附上完整能力清单，
     * 不存在第二处「对外话术」，也就不会出现「介绍里说能做、注册表里其实没有」的分叉。
     */
    public String introduce() {
        return "我是 Rover Ops Agent：一个只读的运维诊断 Agent，基于 Gateway 路由、服务实例、实时指标、抽样追踪、"
                + "生效配置与注册事件回答状态问题、调查调用失败；日志、知识检索与网关写操作尚未接入。\n"
                + "可以直接这样问我：\n"
                + "- 「网关 QPS 多少」「order-service 有几个健康实例」——状态查询；\n"
                + "- 「为什么 /api/demo/tt 调用失败」——只读故障调查；\n"
                + "- 「限流阈值是多少」「最近有哪些实例上下线」——配置与事件查询；\n"
                + "- 「你能做什么」——完整能力清单。\n"
                + describe();
    }

    /** 供证据来源说明使用（与既有数据源命名保持一致）。 */
    public String describeSources() {
        return String.join("、", SOURCE_ROUTES, SOURCE_INSTANCES, SOURCE_METRICS, SOURCE_TRACES,
                SOURCE_CONFIGS, SOURCE_EVENTS);
    }
}