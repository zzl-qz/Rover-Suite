package com.rover.agent.core.capability;

import com.rover.agent.core.investigation.EvidenceNarration;
import com.rover.agent.core.investigation.EvidenceNarrator;
import com.rover.agent.core.investigation.RouteMatcher;
import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.EvidenceType;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.TargetType;
import com.rover.agent.core.port.InstanceReadPort;
import com.rover.agent.core.port.MetricReadPort;
import com.rover.agent.core.port.RouteReadPort;
import com.rover.agent.core.port.TraceReadPort;
import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.core.snapshot.GatewayMetricSnapshot;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import com.rover.agent.core.snapshot.TraceSnapshot;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 只读能力执行器：把「能力标识」翻译成对只读端口的一次真实调用，并产出可追溯的证据。
 *
 * 这是模型与生产数据之间唯一的执行口：模型只能提出能力请求（{@link AgentCapability}），
 * 真实调用、取数口径、证据表述都由这里的代码固定下来。管理口地址、HTTP 客户端与凭证
 * 从不进入提示词，也不出现在能力清单里。
 *
 * 执行失败不抛异常：数据不可用是调查的常见事实，转成判断边界（limitation）与失败步骤如实上报，
 * 让结论说清「数据不足」而不是让整次调查失败。
 */
public final class CapabilityExecutor {

    private static final Logger log = LoggerFactory.getLogger(CapabilityExecutor.class);

    /** Gateway 指标窗口：与证据来源 {@code /api/live?range=60} 保持一致。 */
    public static final int METRIC_WINDOW_SECONDS = 60;

    private static final String SOURCE_ROUTES = "Gateway 路由表";
    private static final String SOURCE_OVERVIEW = "Gateway 概览";
    private static final String SOURCE_INSTANCES = "Nameserver 实例注册表";
    private static final String SOURCE_METRICS = "Gateway 实时指标";
    private static final String SOURCE_TRACES = "Gateway 抽样追踪";

    private final RouteReadPort routes;
    private final InstanceReadPort instances;
    private final MetricReadPort metrics;
    private final TraceReadPort traces;
    private final CapabilityRegistry registry;

    public CapabilityExecutor(RouteReadPort routes, InstanceReadPort instances, MetricReadPort metrics,
                              TraceReadPort traces, CapabilityRegistry registry) {
        this.routes = routes;
        this.instances = instances;
        this.metrics = metrics;
        this.traces = traces;
        this.registry = registry == null ? CapabilityRegistry.standard() : registry;
    }

    /**
     * 执行一次只读能力。
     *
     * @param capability 能力标识；未注册或未接入时不会被调用
     * @param target     本次请求的目标对象；全局能力允许未知目标
     * @param path       取数用的请求路径（路由口径）；为空时按目标取值推断
     * @param taskId     产出证据归属的任务
     */
    public CapabilityResult execute(AgentCapability capability, ResourceTarget target, String path, String taskId) {
        return execute(capability, target, path, taskId, null);
    }

    /**
     * 执行一次只读能力，并带上本次调查已经读到的路由事实。
     *
     * 能力之间彼此独立，但取数口径必须与已确认的事实一致：实例数据按「该路由的目标服务与分组」
     * 统计，而不是按用户描述里的服务名——两者不一致时（例如路由指向 demo/11 而用户只说了
     * demo-service）证据会各说各话。路由事实由调用方在读完路由后传入；未读到时传 {@code null}。
     *
     * @param route 已读到的路由快照；为 {@code null} 表示本次没有可用路由事实
     */
    public CapabilityResult execute(AgentCapability capability, ResourceTarget target, String path, String taskId,
                                    RouteSnapshot route) {
        CapabilityDescriptor descriptor = registry.descriptor(capability).orElse(null);
        if (descriptor == null || !descriptor.available()) {
            return CapabilityResult.unavailable(capability, descriptor);
        }
        ResourceTarget resolved = target == null ? ResourceTarget.unknown() : target;
        String lookup = lookupPath(resolved, path);
        return switch (capability) {
            case ROUTE_QUERY -> readRoute(capability, descriptor, lookup, taskId);
            case INSTANCE_QUERY -> readInstances(capability, descriptor, resolved, route, taskId);
            case GATEWAY_METRICS_QUERY -> readMetrics(capability, descriptor, taskId);
            case TRACE_QUERY -> readTraces(capability, descriptor, lookup, taskId);
            default -> CapabilityResult.unavailable(capability, descriptor);
        };
    }

    /** 取数路径：调用方给出的路径优先，其次用路由目标的取值。 */
    private static String lookupPath(ResourceTarget target, String path) {
        if (path != null && !path.isBlank()) {
            return path.trim();
        }
        return target.type() == TargetType.ROUTE ? target.value() : "";
    }

    private CapabilityResult readRoute(AgentCapability capability, CapabilityDescriptor descriptor, String path,
                                       String taskId) {
        List<Evidence> evidence = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        RouteSnapshot route = null;
        boolean routeRead = false;
        long observedAt = System.currentTimeMillis();
        try {
            route = RouteMatcher.match(routes.routes(), path);
            EvidenceNarration narration = EvidenceNarrator.route(route);
            evidence.add(Evidence.of(taskId, EvidenceType.ROUTE, SOURCE_ROUTES, "路由匹配", narration.detail(),
                    "/api/routes", observedAt));
            limitations.addAll(narration.limitations());
            routeRead = true;
        } catch (Exception ex) {
            log.warn("Agent 读取路由失败", ex);
            limitations.add(EvidenceNarrator.routeUnavailable());
        }
        DiscoveryMode discovery = DiscoveryMode.UNKNOWN;
        try {
            discovery = routes.discoveryMode();
        } catch (Exception ex) {
            log.warn("Agent 读取服务发现模式失败", ex);
        }
        EvidenceNarration discoveryNarration = EvidenceNarrator.discoveryMode(discovery);
        evidence.add(Evidence.of(taskId, EvidenceType.ROUTE, SOURCE_OVERVIEW, "服务发现模式",
                discoveryNarration.detail(), "/api/overview", observedAt));
        limitations.addAll(discoveryNarration.limitations());
        return CapabilityResult.success(capability, descriptor, evidence, limitations)
                .withRoute(route, routeRead, discovery);
    }

    private CapabilityResult readInstances(AgentCapability capability, CapabilityDescriptor descriptor,
                                           ResourceTarget target, RouteSnapshot route, String taskId) {
        try {
            List<InstanceSnapshot> snapshot = instances.instances();
            // 口径优先级：已读到的路由事实 > 目标本身是服务 > 全部注册实例（全局能力）。
            EvidenceNarration narration = route != null
                    ? EvidenceNarrator.instances(snapshot, route)
                    : EvidenceNarrator.instances(snapshot,
                            target.type() == TargetType.SERVICE ? target.value() : "", "");
            return CapabilityResult.success(capability, descriptor,
                            List.of(Evidence.of(taskId, EvidenceType.INSTANCE, SOURCE_INSTANCES, "实例健康",
                                    narration.detail(), "/api/instances", System.currentTimeMillis())),
                            narration.limitations())
                    .withInstances(snapshot);
        } catch (Exception ex) {
            log.warn("Agent 读取实例失败", ex);
            return CapabilityResult.failed(capability, descriptor, EvidenceNarrator.instancesUnavailable());
        }
    }

    private CapabilityResult readMetrics(AgentCapability capability, CapabilityDescriptor descriptor, String taskId) {
        try {
            GatewayMetricSnapshot metric = metrics.gatewayWindow(METRIC_WINDOW_SECONDS);
            EvidenceNarration narration = EvidenceNarrator.metrics(metric);
            return CapabilityResult.success(capability, descriptor,
                    List.of(Evidence.of(taskId, EvidenceType.METRIC, SOURCE_METRICS, "最近一分钟流量",
                            narration.detail(), "/api/live?range=" + METRIC_WINDOW_SECONDS,
                            System.currentTimeMillis())),
                    narration.limitations())
                    .withMetric(metric);
        } catch (Exception ex) {
            log.warn("Agent 读取指标失败", ex);
            return CapabilityResult.failed(capability, descriptor, EvidenceNarrator.metricsUnavailable());
        }
    }

    private CapabilityResult readTraces(AgentCapability capability, CapabilityDescriptor descriptor, String path,
                                        String taskId) {
        if (path.isBlank()) {
            return CapabilityResult.failed(capability, descriptor, "缺少可调查的请求路径，无法读取追踪数据。");
        }
        try {
            TraceSnapshot snapshot = traces.byPath(path);
            EvidenceNarration narration = EvidenceNarrator.traces(snapshot, path);
            return CapabilityResult.success(capability, descriptor,
                            List.of(Evidence.of(taskId, EvidenceType.TRACE, SOURCE_TRACES, "路径追踪记录",
                                    narration.detail(),
                                    "/api/traces?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8),
                                    System.currentTimeMillis())),
                            narration.limitations())
                    .withTraces(snapshot);
        } catch (Exception ex) {
            log.warn("Agent 读取追踪失败", ex);
            return CapabilityResult.failed(capability, descriptor, EvidenceNarrator.tracesUnavailable());
        }
    }
}