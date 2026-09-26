package com.rover.agent.core.capability;

import com.rover.agent.core.investigation.EvidenceNarration;
import com.rover.agent.core.investigation.EvidenceNarrator;
import com.rover.agent.core.investigation.RouteMatcher;
import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.EvidenceType;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.TargetType;
import com.rover.agent.core.port.ConfigReadPort;
import com.rover.agent.core.port.EventReadPort;
import com.rover.agent.core.port.InstanceReadPort;
import com.rover.agent.core.port.MetricReadPort;
import com.rover.agent.core.port.RouteReadPort;
import com.rover.agent.core.port.TraceReadPort;
import com.rover.agent.core.snapshot.ConfigEntrySnapshot;
import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.core.snapshot.GatewayMetricSnapshot;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RegistryEventSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import com.rover.agent.core.snapshot.RouteUpstreamSnapshot;
import com.rover.agent.core.snapshot.TraceSnapshot;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * 让结论说清「数据不足」而不是让整次调查失败。所有证据都带上统计口径元数据
 * （窗口秒数、样本量、取证时刻），样本不足的结论因此可以从证据本身复核。
 */
public final class CapabilityExecutor {

    private static final Logger log = LoggerFactory.getLogger(CapabilityExecutor.class);

    /** Gateway 指标窗口：与证据来源 {@code /api/live?range=60} 同口径，常量唯一出处见证据表述层。 */
    public static final int METRIC_WINDOW_SECONDS = EvidenceNarrator.METRIC_WINDOW_SECONDS;

    /** 单条「路由 × 上游实例」能力最多列举的实例证据条数：证据要能复核，但不能把结论塞满。 */
    private static final int MAX_UPSTREAM_EVIDENCE = 10;

    private static final String SOURCE_ROUTES = "Gateway 路由表";
    private static final String SOURCE_OVERVIEW = "Gateway 概览";
    private static final String SOURCE_INSTANCES = "Nameserver 实例注册表";
    private static final String SOURCE_METRICS = "Gateway 实时指标";
    private static final String SOURCE_TRACES = "Gateway 抽样追踪";
    private static final String SOURCE_CONFIGS = "Gateway / Nameserver 生效配置";
    private static final String SOURCE_EVENTS = "Nameserver 事件流";

    private static final String KEY_WINDOW_SECONDS = "windowSeconds";
    private static final String KEY_SAMPLE_SIZE = "sampleSize";
    private static final String KEY_OBSERVED_AT = "observedAtMillis";
    private static final String KEY_ROUTE_ID = "routeId";
    private static final String KEY_HOST_PORT = "hostPort";
    private static final String KEY_COMPONENT = "component";

    private final RouteReadPort routes;
    private final InstanceReadPort instances;
    private final MetricReadPort metrics;
    private final TraceReadPort traces;
    private final ConfigReadPort configs;
    private final EventReadPort events;
    private final CapabilityRegistry registry;

    public CapabilityExecutor(RouteReadPort routes, InstanceReadPort instances, MetricReadPort metrics,
                              TraceReadPort traces, ConfigReadPort configs, EventReadPort events,
                              CapabilityRegistry registry) {
        this.routes = routes;
        this.instances = instances;
        this.metrics = metrics;
        this.traces = traces;
        this.configs = configs;
        this.events = events;
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
            case GATEWAY_METRICS_QUERY -> readMetrics(capability, descriptor, route, lookup, taskId);
            case TRACE_QUERY -> readTraces(capability, descriptor, lookup, taskId);
            case CONFIG_READ -> readConfigs(capability, descriptor, taskId);
            case EVENT_QUERY -> readEvents(capability, descriptor, taskId);
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
                    "/api/routes", metadata(narration, observedAt,
                            KEY_ROUTE_ID, route == null ? "" : text(route.routeId())), observedAt));
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
                discoveryNarration.detail(), "/api/overview", metadata(discoveryNarration, observedAt), observedAt));
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
            long observedAt = System.currentTimeMillis();
            return CapabilityResult.success(capability, descriptor,
                            List.of(Evidence.of(taskId, EvidenceType.INSTANCE, SOURCE_INSTANCES, "实例健康",
                                    narration.detail(), "/api/instances",
                                    metadata(narration, observedAt,
                                            KEY_ROUTE_ID, route == null ? "" : text(route.routeId())), observedAt)),
                            narration.limitations())
                    .withInstances(snapshot);
        } catch (Exception ex) {
            log.warn("Agent 读取实例失败", ex);
            return CapabilityResult.failed(capability, descriptor, EvidenceNarrator.instancesUnavailable());
        }
    }

    /**
     * 指标取数：全局窗口指标 + 「路由 × 上游实例」窗口观测。
     *
     * 全局指标回答「这条链路整体有没有异常」，按上游实例的观测回答「是哪台实例异常」——后者才是
     * 处置决策的依据。调用方没有传路由时，这里用本地的路由匹配补一次，避免模型把指标能力排在路由
     * 能力之前时丢掉实例维度；路由确实取不到时只记录判断边界，不影响全局指标的产出。
     */
    private CapabilityResult readMetrics(AgentCapability capability, CapabilityDescriptor descriptor,
                                         RouteSnapshot route, String lookup, String taskId) {
        long observedAt = System.currentTimeMillis();
        List<Evidence> evidence = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        GatewayMetricSnapshot metric;
        try {
            metric = metrics.gatewayWindow(METRIC_WINDOW_SECONDS);
        } catch (Exception ex) {
            log.warn("Agent 读取指标失败", ex);
            return CapabilityResult.failed(capability, descriptor, EvidenceNarrator.metricsUnavailable());
        }
        EvidenceNarration narration = EvidenceNarrator.metrics(metric);
        evidence.add(Evidence.of(taskId, EvidenceType.METRIC, SOURCE_METRICS, "最近一分钟流量",
                narration.detail(), "/api/live?range=" + METRIC_WINDOW_SECONDS,
                metadata(narration, observedAt), observedAt));
        limitations.addAll(narration.limitations());

        String routeId = resolveRouteId(route, lookup, limitations);
        List<RouteUpstreamSnapshot> upstreams = List.of();
        if (!routeId.isBlank()) {
            upstreams = readRouteUpstreams(routeId, taskId, observedAt, evidence, limitations);
        }
        return CapabilityResult.success(capability, descriptor, evidence, limitations)
                .withMetric(metric)
                .withRouteUpstreams(upstreams);
    }

    /** 本次指标证据要归因到哪条路由：优先用已读到的路由事实，其次按路径补一次匹配。 */
    private String resolveRouteId(RouteSnapshot route, String lookup, List<String> limitations) {
        if (route != null && !text(route.routeId()).isBlank()) {
            return text(route.routeId());
        }
        if (lookup == null || lookup.isBlank()) {
            return "";
        }
        try {
            RouteSnapshot matched = RouteMatcher.match(routes.routes(), lookup);
            return matched == null ? "" : text(matched.routeId());
        } catch (Exception ex) {
            log.warn("Agent 按路径补匹配路由失败", ex);
            limitations.add(EvidenceNarrator.routeUnavailable());
            return "";
        }
    }

    /**
     * 读取「路由 × 上游实例」窗口观测，并把每个实例的观测作为独立证据。
     *
     * 取数失败只降级为判断边界：一条路由的实例维度指标不可用，不该让全局指标证据一起作废。
     */
    private List<RouteUpstreamSnapshot> readRouteUpstreams(String routeId, String taskId, long observedAt,
                                                           List<Evidence> evidence, List<String> limitations) {
        List<RouteUpstreamSnapshot> rows;
        try {
            rows = metrics.routeUpstreams(routeId, METRIC_WINDOW_SECONDS);
        } catch (Exception ex) {
            log.warn("Agent 读取按上游实例指标失败", ex);
            limitations.add(EvidenceNarrator.routeUpstreamsUnavailable(routeId));
            return List.of();
        }
        List<RouteUpstreamSnapshot> sorted = rows == null ? List.of() : rows.stream()
                .filter(item -> item != null)
                .sorted(Comparator.comparingLong(RouteUpstreamSnapshot::windowRequests).reversed()
                        .thenComparing(Comparator.comparingLong(RouteUpstreamSnapshot::status5xx).reversed()))
                .toList();
        if (sorted.isEmpty()) {
            EvidenceNarration empty = EvidenceNarrator.routeUpstreamsEmpty(routeId, METRIC_WINDOW_SECONDS);
            evidence.add(Evidence.of(taskId, EvidenceType.METRIC, SOURCE_METRICS, "上游实例窗口观测",
                    empty.detail(), "/api/metrics/routes?routeId=" + urlEncode(routeId),
                    metadata(empty, observedAt, KEY_ROUTE_ID, routeId), observedAt));
            limitations.addAll(empty.limitations());
            return List.of();
        }
        List<RouteUpstreamSnapshot> listed = sorted.size() > MAX_UPSTREAM_EVIDENCE
                ? sorted.subList(0, MAX_UPSTREAM_EVIDENCE) : sorted;
        for (RouteUpstreamSnapshot row : listed) {
            EvidenceNarration item = EvidenceNarrator.routeUpstream(row);
            evidence.add(Evidence.of(taskId, EvidenceType.METRIC, SOURCE_METRICS, "上游实例窗口观测",
                    item.detail(), "/api/metrics/routes?routeId=" + urlEncode(routeId),
                    metadata(item, observedAt, KEY_ROUTE_ID, routeId, KEY_HOST_PORT, text(row.hostPort())),
                    observedAt));
            limitations.addAll(item.limitations());
        }
        if (sorted.size() > MAX_UPSTREAM_EVIDENCE) {
            limitations.add("该路由有 " + sorted.size() + " 个上游实例存在窗口观测，证据只列举请求数最多的 "
                    + MAX_UPSTREAM_EVIDENCE + " 个。");
        }
        return sorted;
    }

    /**
     * 配置读取：按组件聚合，每个组件一条证据。
     *
     * 配置是时点快照（窗口口径为 0），因此样本量记为该组件的配置条目数。读取失败是能力级失败：
     * 拿不到配置就无法确认当前生效值，不做局部降级猜测。
     */
    private CapabilityResult readConfigs(AgentCapability capability, CapabilityDescriptor descriptor,
                                         String taskId) {
        List<ConfigEntrySnapshot> snapshot;
        try {
            snapshot = configs.configs();
        } catch (Exception ex) {
            log.warn("Agent 读取配置失败", ex);
            return CapabilityResult.failed(capability, descriptor, EvidenceNarrator.configsUnavailable());
        }
        List<ConfigEntrySnapshot> rows = snapshot == null ? List.of() : snapshot;
        long observedAt = System.currentTimeMillis();
        List<Evidence> evidence = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        Map<String, List<ConfigEntrySnapshot>> grouped = new LinkedHashMap<>();
        for (ConfigEntrySnapshot entry : rows) {
            if (entry == null) {
                continue;
            }
            grouped.computeIfAbsent(text(entry.component()), key -> new ArrayList<>()).add(entry);
        }
        if (grouped.isEmpty()) {
            limitations.add("当前没有读取到任何配置项，无法确认生效配置。");
        }
        grouped.forEach((component, entries) -> {
            EvidenceNarration narration = EvidenceNarrator.configs(component, entries);
            evidence.add(Evidence.of(taskId, EvidenceType.CONFIG, SOURCE_CONFIGS, "生效配置", narration.detail(),
                    "/api/configs", metadata(narration, observedAt, KEY_COMPONENT, component), observedAt));
            limitations.addAll(narration.limitations());
        });
        return CapabilityResult.success(capability, descriptor, evidence, limitations).withConfigs(rows);
    }

    /**
     * 事件查询：注册中心最近事件。
     *
     * 事件缓冲区按条数滚动，因此「最近窗口内的条数」与「缓冲区总条数」由证据表述分别说明，
     * 判定只认窗口内的部分。
     */
    private CapabilityResult readEvents(AgentCapability capability, CapabilityDescriptor descriptor,
                                        String taskId) {
        List<RegistryEventSnapshot> snapshot;
        try {
            snapshot = events.events();
        } catch (Exception ex) {
            log.warn("Agent 读取注册事件失败", ex);
            return CapabilityResult.failed(capability, descriptor, EvidenceNarrator.eventsUnavailable());
        }
        List<RegistryEventSnapshot> rows = snapshot == null ? List.of() : snapshot;
        long observedAt = System.currentTimeMillis();
        EvidenceNarration narration = EvidenceNarrator.events(rows, observedAt);
        return CapabilityResult.success(capability, descriptor,
                        List.of(Evidence.of(taskId, EvidenceType.EVENT, SOURCE_EVENTS, "注册中心事件",
                                narration.detail(), "/api/events", metadata(narration, observedAt), observedAt)),
                        narration.limitations())
                .withEvents(rows);
    }

    private CapabilityResult readTraces(AgentCapability capability, CapabilityDescriptor descriptor, String path,
                                        String taskId) {
        if (path.isBlank()) {
            return CapabilityResult.failed(capability, descriptor, "缺少可调查的请求路径，无法读取追踪数据。");
        }
        try {
            TraceSnapshot snapshot = traces.byPath(path);
            EvidenceNarration narration = EvidenceNarrator.traces(snapshot, path);
            long observedAt = System.currentTimeMillis();
            return CapabilityResult.success(capability, descriptor,
                            List.of(Evidence.of(taskId, EvidenceType.TRACE, SOURCE_TRACES, "路径追踪记录",
                                    narration.detail(),
                                    "/api/traces?path=" + urlEncode(path),
                                    metadata(narration, observedAt), observedAt)),
                            narration.limitations())
                    .withTraces(snapshot);
        } catch (Exception ex) {
            log.warn("Agent 读取追踪失败", ex);
            return CapabilityResult.failed(capability, descriptor, EvidenceNarrator.tracesUnavailable());
        }
    }

    /**
     * 证据的统计口径元数据：窗口秒数、样本量与取证时刻是每份证据的最小口径。
     *
     * {@code keyValues} 按「键、值」成对追加定位字段（路由、实例、组件等），奇数个值会被忽略。
     */
    private static Map<String, String> metadata(EvidenceNarration narration, long observedAtMillis,
                                                String... keyValues) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(KEY_WINDOW_SECONDS, String.valueOf(narration.windowSeconds()));
        values.put(KEY_SAMPLE_SIZE, String.valueOf(narration.sampleSize()));
        values.put(KEY_OBSERVED_AT, String.valueOf(observedAtMillis));
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            values.put(keyValues[index], keyValues[index + 1] == null ? "" : keyValues[index + 1]);
        }
        return values;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}