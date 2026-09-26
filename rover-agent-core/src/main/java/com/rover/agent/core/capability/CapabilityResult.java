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

/**
 * 能力执行结果：一次只读能力调用的结构化产出。
 *
 * 证据是给人与模型看的文本事实；{@code route / instances / traces / metric / routeUpstreams / configs /
 * events} 等结构化快照则供确定性规则（InvestigationRules）与状态查询在合成阶段使用——两者来自同一次取数，
 * 口径必然一致。
 *
 * {@code executed} 表示这次能力是否真的被调用过：未接入数据适配器的能力不会被调用（false），
 * 调用失败的能力仍是「执行过」（true），只是证据为空并带出判断边界。
 *
 * @param capability     能力标识
 * @param stepType       上报的步骤类型
 * @param stepName       上报的步骤名
 * @param executed       是否实际执行
 * @param evidence       产出的证据；调用失败或不可用时为空
 * @param limitations    判断边界（数据不可用、口径限制等）
 * @param route          命中的路由；未取到时为 null
 * @param routeRead      路由表是否读取成功（区分「没有匹配」与「数据不可用」）
 * @param discoveryMode  上游服务发现模式
 * @param instances      实例快照；未取到时为 null
 * @param traces         追踪快照；未取到时为 null
 * @param metric         全局指标快照；未取到时为 null
 * @param routeUpstreams 指定路由下各上游实例的窗口观测；未取到时为 null
 * @param configs        生效配置快照；未取到时为 null
 * @param events         注册中心事件快照；未取到时为 null
 */
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
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
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
