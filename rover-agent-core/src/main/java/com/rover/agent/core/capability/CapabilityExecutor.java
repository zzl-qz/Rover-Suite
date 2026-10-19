package com.rover.agent.core.capability;

import com.rover.agent.core.investigation.EvidenceNarration;
import com.rover.agent.core.investigation.EvidenceNarrator;
import com.rover.agent.core.investigation.RouteMatcher;
import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.EvidenceType;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.TargetType;
import com.rover.agent.core.model.TimeRange;
import com.rover.agent.core.port.ConfigReadPort;
import com.rover.agent.core.port.EventReadPort;
import com.rover.agent.core.port.InstanceReadPort;
import com.rover.agent.core.port.KnowledgeEntry;
import com.rover.agent.core.port.KnowledgeReadPort;
import com.rover.agent.core.port.LogEntry;
import com.rover.agent.core.port.LogQueryPort;
import com.rover.agent.core.port.LogRequest;
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
import com.rover.agent.core.util.Texts;
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
 * 执行只读能力并生成包含窗口、样本量和取证时间的证据。
 * 读取失败记录为失败步骤和判断边界；管理口地址与凭证不进入提示词。
 */
public final class CapabilityExecutor {

    private static final Logger log = LoggerFactory.getLogger(CapabilityExecutor.class);

    /** Gateway 指标窗口：与证据来源 {@code /api/live?range=60} 同口径，常量唯一出处见证据表述层。 */
    public static final int METRIC_WINDOW_SECONDS = EvidenceNarrator.METRIC_WINDOW_SECONDS;

    /** 单条「路由 × 上游实例」能力最多列举的实例证据条数：证据要能复核，但不能把结论塞满。 */
    private static final int MAX_UPSTREAM_EVIDENCE = 10;

    /** 历史日志单次查询最多取回条数：证据要能复核，但不能把整表塞进结论。 */
    private static final int LOG_LIMIT = 50;

    /** 知识检索单次最多返回条数：回答「怎么配置」时给出最相关几条即可。 */
    private static final int KNOWLEDGE_LIMIT = 5;

    private static final String SOURCE_ROUTES = "Gateway 路由表";
    private static final String SOURCE_OVERVIEW = "Gateway 概览";
    private static final String SOURCE_INSTANCES = "Nameserver 实例注册表";
    private static final String SOURCE_METRICS = "Gateway 实时指标";
    private static final String SOURCE_TRACES = "Gateway 抽样追踪";
    private static final String SOURCE_CONFIGS = "Gateway / Nameserver 生效配置";
    private static final String SOURCE_EVENTS = "Nameserver 事件流";
    private static final String SOURCE_LOGS = "落盘历史日志";
    private static final String SOURCE_KNOWLEDGE = "运维知识库";

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
    private final LogQueryPort logs;
    private final KnowledgeReadPort knowledge;
    private final CapabilityRegistry registry;
    private final java.util.function.Consumer<String> authorize;
    private final java.util.function.Function<String, com.rover.agent.core.model.InvestigationContext> contexts;

    public CapabilityExecutor(RouteReadPort routes, InstanceReadPort instances, MetricReadPort metrics,
                              TraceReadPort traces, ConfigReadPort configs, EventReadPort events,
                              LogQueryPort logs, KnowledgeReadPort knowledge, CapabilityRegistry registry) {
        this(routes, instances, metrics, traces, configs, events, logs, knowledge, registry, taskId -> { });
    }

    public CapabilityExecutor(RouteReadPort routes, InstanceReadPort instances, MetricReadPort metrics,
                              TraceReadPort traces, ConfigReadPort configs, EventReadPort events,
                              LogQueryPort logs, KnowledgeReadPort knowledge, CapabilityRegistry registry,
                              java.util.function.Consumer<String> authorize) {
        this(routes, instances, metrics, traces, configs, events, logs, knowledge, registry, authorize,
                com.rover.agent.core.model.InvestigationContext::legacy);
    }

    public CapabilityExecutor(RouteReadPort routes, InstanceReadPort instances, MetricReadPort metrics,
                              TraceReadPort traces, ConfigReadPort configs, EventReadPort events,
                              LogQueryPort logs, KnowledgeReadPort knowledge, CapabilityRegistry registry,
                              java.util.function.Consumer<String> authorize,
                              java.util.function.Function<String, com.rover.agent.core.model.InvestigationContext> contexts) {
        this.routes = routes;
        this.instances = instances;
        this.metrics = metrics;
        this.traces = traces;
        this.configs = configs;
        this.events = events;
        this.logs = logs;
        this.knowledge = knowledge;
        this.registry = registry == null ? CapabilityRegistry.standard() : registry;
        this.authorize = authorize;
        this.contexts = contexts;
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
     * 按已读取的路由服务与分组执行只读能力；无路由事实时传 null。
     *
     * @param route 已读到的路由快照；为 {@code null} 表示本次没有可用路由事实
     */
    public CapabilityResult execute(AgentCapability capability, ResourceTarget target, String path, String taskId,
                                    RouteSnapshot route) {
        return execute(capability, target, path, taskId, route, TimeRange.unspecified());
    }

    /** 指定时间范围贯穿工具调用；不支持绝对历史窗口的实时指标明确返回不可用。 */
    public CapabilityResult execute(AgentCapability capability, ResourceTarget target, String path, String taskId,
                                    RouteSnapshot route, TimeRange timeRange) {
        if (capability != AgentCapability.LOG_QUERY) { authorize.accept(taskId); }
        TimeRange range = timeRange == null ? TimeRange.unspecified() : timeRange;
        CapabilityDescriptor descriptor = registry.descriptor(capability).orElse(null);
        if (descriptor == null || !descriptor.available()) {
            return CapabilityResult.unavailable(capability, descriptor);
        }
        ResourceTarget resolved = target == null ? ResourceTarget.unknown() : target;
        String lookup = lookupPath(resolved, path);
        CapabilityResult result = switch (capability) {
            case ROUTE_QUERY -> readRoute(capability, descriptor, lookup, taskId);
            case INSTANCE_QUERY -> readInstances(capability, descriptor, resolved, route, taskId);
            case GATEWAY_METRICS_QUERY -> range.isUnspecified()
                    ? readMetrics(capability, descriptor, route, lookup, taskId)
                    : readHistoricalWindow(capability, descriptor, route, lookup, taskId, range);
            case TRACE_QUERY -> readTraces(capability, descriptor, lookup, taskId, range);
            case CONFIG_READ -> readConfigs(capability, descriptor, taskId);
            case EVENT_QUERY -> readEvents(capability, descriptor, taskId, range);
            case LOG_QUERY -> queryLogs(lookup, null,
                    range.isUnspecified() ? System.currentTimeMillis() - 24L * 3_600_000L : range.fromMillis(),
                    range.isUnspecified() ? null : range.toMillis(), taskId);
            case KNOWLEDGE_RETRIEVAL -> readKnowledge(capability, descriptor, lookup, taskId);
            default -> CapabilityResult.unavailable(capability, descriptor);
        };
        return enrich(result.inTimeRange(range), contexts.apply(taskId).query(resolved, range), taskId);
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
            if (path.isBlank()) {
                // 无路径 = 列全部路由：「一共有几条路由」必须一次拿全。只给按路径查的话，
                // 模型只能靠猜前缀逐个探测，猜不到的前缀会被漏掉，而它并不自知。
                EvidenceNarration narration = EvidenceNarrator.routes(routes.routes());
                evidence.add(Evidence.of(taskId, EvidenceType.ROUTE, SOURCE_ROUTES, "路由清单",
                        narration.detail(), "/api/routes", metadata(narration, observedAt), observedAt));
                limitations.addAll(narration.limitations());
            } else {
                route = RouteMatcher.match(routes.routes(), path);
                EvidenceNarration narration = EvidenceNarrator.route(route);
                evidence.add(Evidence.of(taskId, EvidenceType.ROUTE, SOURCE_ROUTES, "路由匹配", narration.detail(),
                        "/api/routes", metadata(narration, observedAt,
                                KEY_ROUTE_ID, route == null ? "" : Texts.orEmpty(route.routeId())), observedAt));
                limitations.addAll(narration.limitations());
            }
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
                                            KEY_ROUTE_ID, route == null ? "" : Texts.orEmpty(route.routeId())), observedAt)),
                            narration.limitations())
                    .withInstances(snapshot);
        } catch (Exception ex) {
            log.warn("Agent 读取实例失败", ex);
            return CapabilityResult.failed(capability, descriptor, EvidenceNarrator.instancesUnavailable());
        }
    }

    /**
     * 读取全局指标和路由的上游实例观测；缺少路由时补做匹配。
     * 路由不可用时记录判断边界，保留全局指标。
     */
    private CapabilityResult readMetrics(AgentCapability capability, CapabilityDescriptor descriptor,
                                         RouteSnapshot route, String lookup, String taskId) {
        long observedAt = System.currentTimeMillis();
        List<Evidence> evidence = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        GatewayMetricSnapshot metric = null;
        try {
            metric = metrics.gatewayWindow(METRIC_WINDOW_SECONDS);
        } catch (Exception ex) {
            log.warn("Agent 读取指标失败", ex);
            limitations.add(EvidenceNarrator.metricsUnavailable());
        }
        if (metric != null) {
            EvidenceNarration narration = EvidenceNarrator.metrics(metric);
            evidence.add(Evidence.of(taskId, EvidenceType.METRIC, SOURCE_METRICS, "最近一分钟流量",
                    narration.detail(), "/api/live?range=" + METRIC_WINDOW_SECONDS,
                    metadata(narration, observedAt), observedAt));
            limitations.addAll(narration.limitations());
        }

        String routeId = resolveRouteId(route, lookup, limitations);
        List<RouteUpstreamSnapshot> upstreams = List.of();
        if (!routeId.isBlank()) {
            readFinalWindow(routeId, taskId, evidence, limitations, TimeRange.unspecified());
            upstreams = readRouteUpstreams(routeId, taskId, observedAt, evidence, limitations);
        }
        return CapabilityResult.success(capability, descriptor, evidence, limitations)
                .withMetric(metric)
                .withRouteUpstreams(upstreams);
    }

    /** 本次指标证据要归因到哪条路由：优先用已读到的路由事实，其次按路径补一次匹配。 */
    private String resolveRouteId(RouteSnapshot route, String lookup, List<String> limitations) {
        if (route != null && !Texts.orEmpty(route.routeId()).isBlank()) {
            return Texts.orEmpty(route.routeId());
        }
        if (lookup == null || lookup.isBlank()) {
            return "";
        }
        try {
            RouteSnapshot matched = RouteMatcher.match(routes.routes(), lookup);
            return matched == null ? "" : Texts.orEmpty(matched.routeId());
        } catch (Exception ex) {
            log.warn("Agent 按路径补匹配路由失败", ex);
            limitations.add(EvidenceNarrator.routeUnavailable());
            return "";
        }
    }

    private CapabilityResult readHistoricalWindow(AgentCapability capability, CapabilityDescriptor descriptor,
                                                   RouteSnapshot route, String lookup, String taskId, TimeRange range) {
        List<Evidence> evidence = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        String routeId = resolveRouteId(route, lookup, limitations);
        if (routeId.isBlank()) { return CapabilityResult.failed(capability, descriptor, "缺少明确路由标识，不能回溯最终响应窗口。"); }
        readFinalWindow(routeId, taskId, evidence, limitations, range);
        limitations.add("路由标识通过当前路径配置定位；请求时身份以窗口维度与追踪中的 UID 为准，不能据当前配置重建历史路由映射。");
        return CapabilityResult.success(capability, descriptor, evidence, limitations);
    }

    private void readFinalWindow(String routeId, String taskId, List<Evidence> evidence, List<String> limitations, TimeRange range) {
        long at = System.currentTimeMillis();
        String query = "/api/metrics/routes?routeId=" + urlEncode(routeId) + (range.isUnspecified()
                ? "&range=" + METRIC_WINDOW_SECONDS : "&fromMillis=" + range.fromMillis() + "&toMillis=" + range.toMillis());
        try {
            var window = range.isUnspecified() ? metrics.routeWindow(routeId, METRIC_WINDOW_SECONDS) : metrics.routeWindow(routeId, range);
            List<String> bounds = new ArrayList<>(window.limitations());
            bounds.add("按请求完成时间计数；转发尝试独立计数。HTTP 错误是现象，后端业务内部原因仍需日志证据。");
            String summary = EvidenceNarrator.routeWindow(window);
            var envelope = new com.rover.agent.core.model.EvidenceEnvelope(1, "gateway.final-responses", "METRIC",
                    query, "final-window-v1", window.requestedRange(), window.coverageRange(), window.coverageRange(),
                    window.observedAtMillis(), null, window.samplingMode(), window.finalRequests(), window.qualityStatus(),
                    null, List.of(), window.facts(), bounds, null, null, "gateway-observation-v1");
            evidence.add(Evidence.of(taskId, EvidenceType.METRIC, SOURCE_METRICS, "路由最终响应窗口", summary, query,
                    Map.of(KEY_ROUTE_ID, routeId, KEY_SAMPLE_SIZE, Long.toString(window.finalRequests()),
                            KEY_WINDOW_SECONDS, Integer.toString(range.isUnspecified() ? METRIC_WINDOW_SECONDS : range.windowSeconds()), KEY_OBSERVED_AT,
                            Long.toString(window.observedAtMillis())), at).withEnvelope(envelope));
            limitations.addAll(bounds);
        } catch (Exception ex) {
            String bound = "该路由的原生最终响应窗口不可用，不能回溯指定时间范围；不能用当前指标、累计拒绝数或抽样追踪替代完整请求计数。";
            limitations.add(bound);
            evidence.add(Evidence.of(taskId, EvidenceType.METRIC, SOURCE_METRICS, "路由最终响应窗口不可用", bound,
                    query, Map.of(KEY_ROUTE_ID, routeId), at).withEnvelope(new com.rover.agent.core.model.EvidenceEnvelope(
                            1, "gateway.final-responses", "METRIC", query, "final-window-v1", null, null, null,
                            at, null, "UNKNOWN", 0, com.rover.agent.core.model.EvidenceQuality.UNAVAILABLE,
                            null, List.of(), Map.of(), List.of(bound), null, null, "gateway-observation-v1")));
        }
    }

    private static com.rover.agent.core.model.EvidenceEnvelope traceEnvelope(TraceSnapshot snapshot, String path,
                                                                           TimeRange requested, EvidenceNarration narration) {
        long at = snapshot.observedAtMillis();
        TimeRange range = requested.isUnspecified() ? new TimeRange(Math.max(1, at - 300_000), at) : requested;
        List<Map<String, Object>> rows = new ArrayList<>();
        long first = Long.MAX_VALUE, last = 0;
        for (var row : snapshot.rows() == null ? List.<com.rover.agent.core.snapshot.TraceRow>of() : snapshot.rows()) {
            if (!path.equals(row.path()) || !range.contains(row.startMillis())) { continue; }
            Map<String, Object> fact = new LinkedHashMap<>();
            fact.put("traceId", row.traceId()); fact.put("statusCode", row.statusCode());
            fact.put("startMillis", row.startMillis());
            var o = row.observation();
            if (o != null) {
                fact.put("routeId", o.routeId()); fact.put("resourceUid", o.resourceUid());
                fact.put("generation", o.generation()); fact.put("serviceName", o.serviceName());
                fact.put("group", o.group()); fact.put("hostPort", o.hostPort());
                fact.put("responseOrigin", o.responseOrigin()); fact.put("failurePhase", o.failurePhase());
                fact.put("rejectReason", o.rejectReason()); fact.put("responseComplete", o.responseComplete());
                fact.put("attempts", o.attempts().stream().map(a -> {
                    Map<String, Object> attempt = new LinkedHashMap<>();
                    attempt.put("hostPort", a.hostPort()); attempt.put("serviceName", a.serviceName());
                    attempt.put("group", a.group()); attempt.put("statusCode", a.statusCode());
                    attempt.put("responseOrigin", a.responseOrigin()); attempt.put("failurePhase", a.failurePhase());
                    attempt.put("responseComplete", a.responseComplete()); attempt.put("costMillis", a.costMillis());
                    return attempt;
                }).toList());
            }
            rows.add(fact); first = Math.min(first, row.startMillis()); last = Math.max(last, row.startMillis());
        }
        var quality = !snapshot.enabled() && rows.isEmpty() ? com.rover.agent.core.model.EvidenceQuality.UNAVAILABLE
                : range.toMillis() > at || range.toMillis() < snapshot.collectionStartedAtMillis()
                        ? com.rover.agent.core.model.EvidenceQuality.OUT_OF_RANGE
                        : com.rover.agent.core.model.EvidenceQuality.PARTIAL;
        // 抽样/慢请求优先/环形覆盖均不能证明完整区间；没有 trace 也不能标作完整 EMPTY。
        return new com.rover.agent.core.model.EvidenceEnvelope(1, "gateway.traces", "TRACE", "/api/traces?path="
                + urlEncode(path), "trace-observation-v1", range,
                rows.isEmpty() ? null : new TimeRange(first, last), null, at, null, snapshot.samplingMode(), rows.size(),
                quality, null, List.of(), Map.of("rows", rows, "samplePopulations",
                        com.rover.agent.core.snapshot.TraceWindowStatistics.populations(snapshot, path, requested), "sampleRate", snapshot.sampleRate(),
                        "overwritten", snapshot.overwritten(), "collectionStartedAtMillis", snapshot.collectionStartedAtMillis()),
                narration.limitations(), null, null, "gateway-observation-v1");
    }

    private CapabilityResult enrich(CapabilityResult result, com.rover.agent.core.model.InvestigationContext context,
                                     String taskId) {
        List<Evidence> enriched = new ArrayList<>();
        for (Evidence item : result.evidence()) {
            List<com.rover.agent.core.model.ResourceRef> refs = new ArrayList<>();
            if (item.type() == EvidenceType.ROUTE && result.route() != null) {
                var route = result.route();
                addRef(refs, context, route.routeId(), route.resourceUid(), route.generation());
            }
            Object dimensions = item.envelope().facts().get("dimensions");
            if (dimensions instanceof List<?> rows) {
                for (Object raw : rows) {
                    if (raw instanceof Map<?, ?> row) {
                        addRef(refs, context, item.metadata().get(KEY_ROUTE_ID), (String) row.get("resourceUid"),
                                row.get("generation") instanceof Number n ? n.longValue() : 0);
                    }
                }
            }
            if (item.type() == EvidenceType.TRACE && result.traces() != null) {
                for (var row : result.traces().rows() == null ? List.<com.rover.agent.core.snapshot.TraceRow>of() : result.traces().rows()) {
                    var o = row.observation();
                    TimeRange range = item.envelope().requestedRange();
                    if (o != null && range.contains(row.startMillis())) {
                        addRef(refs, context, o.routeId(), o.resourceUid(), o.generation());
                    }
                }
            }
            enriched.add(item.withEnvelope(item.envelope().inContext(context, refs)));
        }
        return new CapabilityResult(result.capability(), result.stepType(), result.stepName(), result.executed(),
                enriched, result.limitations(), result.route(), result.routeRead(), result.discoveryMode(), result.instances(),
                result.traces(), result.metric(), result.routeUpstreams(), result.configs(), result.events());
    }

    private static void addRef(List<com.rover.agent.core.model.ResourceRef> refs,
                               com.rover.agent.core.model.InvestigationContext context, String id, String uid, long generation) {
        if (context.scope() == null || context.controlTargetId() == null || context.controlTargetId().isBlank()
                || id == null || id.isBlank() || uid == null || uid.isBlank() || generation <= 0) { return; }
        var ref = new com.rover.agent.core.model.ResourceRef(context.scope(), context.controlTargetId(), "GATEWAY_ROUTE",
                id, uid, generation);
        if (!refs.contains(ref)) { refs.add(ref); }
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
                    metadata(item, observedAt, KEY_ROUTE_ID, routeId, KEY_HOST_PORT, Texts.orEmpty(row.hostPort())),
                    observedAt));
            limitations.addAll(item.limitations());
        }
        if (sorted.size() > MAX_UPSTREAM_EVIDENCE) {
            limitations.add("该路由有 " + sorted.size() + " 个上游实例存在窗口观测，证据只列举请求数最多的 "
                    + MAX_UPSTREAM_EVIDENCE + " 个。");
        }
        return sorted;
    }

    /** 按组件生成配置证据，窗口为 0，样本量为配置项数；读取失败不降级。 */
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
            grouped.computeIfAbsent(Texts.orEmpty(entry.component()), key -> new ArrayList<>()).add(entry);
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

    /** 读取注册中心事件，仅使用当前窗口内事件判定。 */
    private CapabilityResult readEvents(AgentCapability capability, CapabilityDescriptor descriptor,
                                        String taskId, TimeRange range) {
        List<RegistryEventSnapshot> snapshot;
        try {
            snapshot = events.events();
        } catch (Exception ex) {
            log.warn("Agent 读取注册事件失败", ex);
            return CapabilityResult.failed(capability, descriptor, EvidenceNarrator.eventsUnavailable());
        }
        List<RegistryEventSnapshot> rows = snapshot == null ? List.of() : snapshot;
        if (!range.isUnspecified()) {
            rows = rows.stream().filter(row -> row != null && range.contains(row.timestampMillis())).toList();
        }
        long observedAt = System.currentTimeMillis();
        EvidenceNarration narration = EvidenceNarrator.events(rows, observedAt, range);
        return CapabilityResult.success(capability, descriptor,
                        List.of(Evidence.of(taskId, EvidenceType.EVENT, SOURCE_EVENTS, "注册中心事件",
                                narration.detail(), "/api/events", metadata(narration, observedAt), observedAt)),
                        narration.limitations())
                .withEvents(rows);
    }

    private CapabilityResult readTraces(AgentCapability capability, CapabilityDescriptor descriptor, String path,
                                        String taskId, TimeRange range) {
        if (path.isBlank()) {
            return CapabilityResult.failed(capability, descriptor, "缺少可调查的请求路径，无法读取追踪数据。");
        }
        try {
            TraceSnapshot snapshot = traces.byPath(path);
            if (!range.isUnspecified() && snapshot.rows() != null) {
                snapshot = snapshot.filtered(snapshot.rows().stream()
                        .filter(row -> row != null && range.contains(row.startMillis())).toList());
            }
            EvidenceNarration narration = EvidenceNarrator.traces(snapshot, path, range);
            long observedAt = System.currentTimeMillis();
            return CapabilityResult.success(capability, descriptor,
                            List.of(Evidence.of(taskId, EvidenceType.TRACE, SOURCE_TRACES, "路径追踪记录",
                                    narration.detail(),
                                    "/api/traces?path=" + urlEncode(path),
                                    metadata(narration, observedAt), observedAt)
                                    .withEnvelope(traceEnvelope(snapshot, path, range, narration))),
                            narration.limitations())
                    .withTraces(snapshot);
        } catch (Exception ex) {
            log.warn("Agent 读取追踪失败", ex);
            return CapabilityResult.failed(capability, descriptor, EvidenceNarrator.tracesUnavailable());
        }
    }

    /**
     * 按目标、类型和时间范围查询历史日志；未知类型忽略。
     *
     * @param target 目标实体（服务名/路由路径/实例），{@code null} 或空表示全部
     * @param types  类型过滤（枚举名字符串），{@code null} 或空表示全部类型
     * @param fromMillis 起始毫秒时间戳，{@code null} 表示不限
     * @param toMillis   截止毫秒时间戳，{@code null} 表示不限
     */
    public CapabilityResult queryLogs(String target, List<String> types, Long fromMillis, Long toMillis,
                                      String taskId) {
        authorize.accept(taskId);
        TimeRange range = fromMillis != null && toMillis != null ? new TimeRange(fromMillis, toMillis) : TimeRange.unspecified();
        return enrich(readLogs(target, types, fromMillis, toMillis, taskId).inTimeRange(range),
                contexts.apply(taskId).query(target != null && target.startsWith("/")
                        ? ResourceTarget.route(target) : ResourceTarget.unknown(), range), taskId);
    }

    private CapabilityResult readLogs(String target, List<String> types, Long fromMillis, Long toMillis, String taskId) {
        CapabilityDescriptor descriptor = registry.descriptor(AgentCapability.LOG_QUERY).orElse(null);
        if (descriptor == null || !descriptor.available()) {
            return CapabilityResult.unavailable(AgentCapability.LOG_QUERY, descriptor);
        }
        long observedAt = System.currentTimeMillis();
        try {
            List<LogEntry> entries = logs.query(new LogRequest(target, fromMillis, toMillis, types, LOG_LIMIT));
            EvidenceNarration narration = EvidenceNarrator.logs(entries, types,
                    fromMillis == null ? 0L : fromMillis, toMillis == null ? 0L : toMillis);
            return CapabilityResult.success(AgentCapability.LOG_QUERY, descriptor,
                    List.of(Evidence.of(taskId, EvidenceType.LOG, SOURCE_LOGS, "历史日志",
                            narration.detail(), "/api/logs", metadata(narration, observedAt), observedAt)),
                    narration.limitations());
        } catch (Exception ex) {
            log.warn("Agent 查询历史日志失败", ex);
            return CapabilityResult.failed(AgentCapability.LOG_QUERY, descriptor, EvidenceNarrator.logsUnavailable());
        }
    }

    /** 检索运维知识，证据不包含实时统计窗口。 */
    private CapabilityResult readKnowledge(AgentCapability capability, CapabilityDescriptor descriptor,
                                          String query, String taskId) {
        if (query == null || query.isBlank()) {
            return CapabilityResult.failed(capability, descriptor, "缺少要检索的问题，无法查询知识库。");
        }
        long observedAt = System.currentTimeMillis();
        try {
            List<KnowledgeEntry> entries = knowledge.search(query, KNOWLEDGE_LIMIT);
            EvidenceNarration narration = EvidenceNarrator.knowledge(entries, query);
            return CapabilityResult.success(capability, descriptor,
                    List.of(Evidence.of(taskId, EvidenceType.KNOWLEDGE, SOURCE_KNOWLEDGE, "知识检索",
                            narration.detail(), "/api/knowledge", metadata(narration, observedAt), observedAt)),
                    narration.limitations());
        } catch (Exception ex) {
            log.warn("Agent 检索知识失败", ex);
            return CapabilityResult.failed(capability, descriptor, EvidenceNarrator.knowledgeUnavailable());
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

}
