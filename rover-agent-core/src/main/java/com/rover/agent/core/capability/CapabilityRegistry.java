package com.rover.agent.core.capability;

import com.rover.agent.core.model.AgentStepType;
import com.rover.agent.core.model.TargetType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 声明可用的只读能力；执行映射由 {@link CapabilityExecutor} 提供。 */
public final class CapabilityRegistry {

    private final List<CapabilityDescriptor> descriptors;

    public CapabilityRegistry(List<CapabilityDescriptor> descriptors) {
        this.descriptors = descriptors == null ? List.of() : List.copyOf(descriptors);
    }

    /** 标准只读能力注册表；available=false 的能力不参与规划。 */
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
                        true, AgentStepType.EVENT_INVESTIGATION, "读取事件"),
                new CapabilityDescriptor(AgentCapability.LOG_QUERY, "历史日志查询",
                        "查询落盘历史日志：配置变更、回滚、错误、实例上下线事件、指标采样与慢/错误链路，"
                                + "支持按类型、目标实体与时间范围过滤",
                        CapabilityRisk.READ_ONLY,
                        List.of(TargetType.UNKNOWN, TargetType.ROUTE, TargetType.SERVICE, TargetType.INSTANCE),
                        true, AgentStepType.LOG_INVESTIGATION, "读取历史日志"),
                new CapabilityDescriptor(AgentCapability.KNOWLEDGE_RETRIEVAL, "知识检索",
                        "检索运维知识库：怎么配置限流/熔断/超时/采样率、怎么接入服务、常见问题怎么排查",
                        CapabilityRisk.READ_ONLY,
                        List.of(TargetType.UNKNOWN, TargetType.ROUTE, TargetType.SERVICE, TargetType.INSTANCE),
                        true, AgentStepType.KNOWLEDGE_INVESTIGATION, "检索知识")));
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
}