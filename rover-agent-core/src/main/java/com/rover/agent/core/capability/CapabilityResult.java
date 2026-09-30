package com.rover.agent.core.capability;

import com.rover.agent.core.model.AgentStepType;
import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.snapshot.ConfigEntrySnapshot;
import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.core.snapshot.GatewayMetricSnapshot;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RegistryEventSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import com.rover.agent.core.snapshot.RouteUpstreamSnapshot;
import com.rover.agent.core.snapshot.TraceSnapshot;
import java.util.ArrayList;
import java.util.List;

/** 能力执行结果：一次只读能力调用的结构化产出，含证据文本与供规则使用的结构化快照。 */
public record CapabilityResult(AgentCapability capability, AgentStepType stepType, String stepName,
                               boolean executed, List<Evidence> evidence, List<String> limitations,
                               RouteSnapshot route, boolean routeRead, DiscoveryMode discoveryMode,
                               List<InstanceSnapshot> instances, TraceSnapshot traces,
                               GatewayMetricSnapshot metric, List<RouteUpstreamSnapshot> routeUpstreams,
                               List<ConfigEntrySnapshot> configs, List<RegistryEventSnapshot> events) {

    public CapabilityResult {
        capability = capability == null ? AgentCapability.ROUTE_QUERY : capability;
        stepType = stepType == null ? AgentStepType.ANSWER : stepType;
        stepName = stepName == null || stepName.isBlank() ? capability.name() : stepName.trim();
        evidence = List.copyOf(stamp(evidence == null ? List.of() : evidence));
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        discoveryMode = discoveryMode == null ? DiscoveryMode.UNKNOWN : discoveryMode;
    }

    /** 执行成功：带证据与判断边界。 */
    public static CapabilityResult success(AgentCapability capability, CapabilityDescriptor descriptor,
                                           List<Evidence> evidence, List<String> limitations) {
        return base(capability, descriptor, true, evidence, limitations);
    }

    /** 执行失败：能力被调用过，但数据不可用；只带判断边界。 */
    public static CapabilityResult failed(AgentCapability capability, CapabilityDescriptor descriptor,
                                          String limitation) {
        return base(capability, descriptor, true, List.of(), List.of(limitation));
    }

    /** 能力不可用：未接入数据适配器，未执行。 */
    public static CapabilityResult unavailable(AgentCapability capability, CapabilityDescriptor descriptor) {
        return base(capability, descriptor, false, List.of(),
                List.of("能力 " + capability + " 当前不可用：尚未接入数据适配器。"));
    }

    private static CapabilityResult base(AgentCapability capability, CapabilityDescriptor descriptor,
                                         boolean executed, List<Evidence> evidence, List<String> limitations) {
        return new CapabilityResult(capability, type(descriptor), name(descriptor), executed, evidence, limitations,
                null, false, DiscoveryMode.UNKNOWN, null, null, null, null, null, null);
    }

    /** 当前线程绑了工具调用号时，给这份结果里的每条证据记上。规划路径没有号，原样返回。 */
    private static List<Evidence> stamp(List<Evidence> evidence) {
        String toolCallId = ToolCallTrace.current();
        if (toolCallId.isEmpty() || evidence.isEmpty()) {
            return evidence;
        }
        List<Evidence> stamped = new ArrayList<>(evidence.size());
        for (Evidence item : evidence) {
            stamped.add(item.withToolCallId(toolCallId));
        }
        return stamped;
    }

    private static AgentStepType type(CapabilityDescriptor descriptor) {
        return descriptor == null ? AgentStepType.ANSWER : descriptor.stepType();
    }

    private static String name(CapabilityDescriptor descriptor) {
        return descriptor == null ? null : descriptor.stepName();
    }

    /** 附上路由事实。 */
    public CapabilityResult withRoute(RouteSnapshot matchedRoute, boolean read, DiscoveryMode mode) {
        return copy(evidence, limitations, matchedRoute, read, mode, instances, traces, metric, routeUpstreams,
                configs, events);
    }

    /** 附上实例快照。 */
    public CapabilityResult withInstances(List<InstanceSnapshot> snapshot) {
        return copy(evidence, limitations, route, routeRead, discoveryMode, snapshot, traces, metric, routeUpstreams,
                configs, events);
    }

    /** 附上追踪快照。 */
    public CapabilityResult withTraces(TraceSnapshot snapshot) {
        return copy(evidence, limitations, route, routeRead, discoveryMode, instances, snapshot, metric, routeUpstreams,
                configs, events);
    }

    /** 附上全局指标快照。 */
    public CapabilityResult withMetric(GatewayMetricSnapshot snapshot) {
        return copy(evidence, limitations, route, routeRead, discoveryMode, instances, traces, snapshot, routeUpstreams,
                configs, events);
    }

    /** 附上「路由 × 上游实例」窗口观测。 */
    public CapabilityResult withRouteUpstreams(List<RouteUpstreamSnapshot> snapshot) {
        return copy(evidence, limitations, route, routeRead, discoveryMode, instances, traces, metric, snapshot,
                configs, events);
    }

    /** 附上生效配置快照。 */
    public CapabilityResult withConfigs(List<ConfigEntrySnapshot> snapshot) {
        return copy(evidence, limitations, route, routeRead, discoveryMode, instances, traces, metric, routeUpstreams,
                snapshot, events);
    }

    /** 附上注册中心事件快照。 */
    public CapabilityResult withEvents(List<RegistryEventSnapshot> snapshot) {
        return copy(evidence, limitations, route, routeRead, discoveryMode, instances, traces, metric, routeUpstreams,
                configs, snapshot);
    }

    /**
     * 追加证据与判断边界：一次能力可以产出多条证据（如按组件拆分的配置快照、按实例拆分的异常观测）。
     */
    public CapabilityResult withEvidence(List<Evidence> extraEvidence, List<String> extraLimitations) {
        List<Evidence> merged = new ArrayList<>(evidence);
        if (extraEvidence != null) {
            merged.addAll(extraEvidence);
        }
        List<String> mergedLimitations = new ArrayList<>(limitations);
        if (extraLimitations != null) {
            mergedLimitations.addAll(extraLimitations);
        }
        return copy(merged, mergedLimitations, route, routeRead, discoveryMode, instances, traces, metric,
                routeUpstreams, configs, events);
    }

    private CapabilityResult copy(List<Evidence> nextEvidence, List<String> nextLimitations, RouteSnapshot nextRoute,
                                  boolean nextRouteRead, DiscoveryMode nextDiscovery,
                                  List<InstanceSnapshot> nextInstances, TraceSnapshot nextTraces,
                                  GatewayMetricSnapshot nextMetric, List<RouteUpstreamSnapshot> nextRouteUpstreams,
                                  List<ConfigEntrySnapshot> nextConfigs, List<RegistryEventSnapshot> nextEvents) {
        return new CapabilityResult(capability, stepType, stepName, executed, nextEvidence, nextLimitations, nextRoute,
                nextRouteRead, nextDiscovery, nextInstances, nextTraces, nextMetric, nextRouteUpstreams, nextConfigs,
                nextEvents);
    }
}
