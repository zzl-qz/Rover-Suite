package com.rover.gateway.core.manage;

import com.rover.common.config.RuntimeConfigManager;
import com.rover.common.constants.HttpConstants;
import com.rover.common.constants.ManageApiPaths;
import com.rover.common.constants.RoverComponent;
import com.rover.common.spi.discovery.ServiceDiscovery;
import com.rover.common.spi.discovery.ServiceDiscoveryStatus;
import com.rover.common.config.ConfigValues;
import com.rover.common.json.JsonCodec;
import com.rover.common.manage.AbstractManageApi;
import com.rover.gateway.core.config.GatewayDefaults;
import com.rover.gateway.core.config.GatewaySystemProperties;
import com.rover.gateway.core.discovery.NameserverServiceDiscovery;
import com.rover.gateway.core.route.RouteConfig;
import com.rover.gateway.core.route.RouteOverlayStore;
import com.rover.gateway.core.route.RouteValidator;
import com.rover.gateway.core.runtime.GatewayRuntime;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Author: Daylight
 * Created: 2026-08-10 09:27:00
 * Description: Gateway 同口管理 API（/_manage/**）：status/routes 端点，路由变更热更新并落盘
 */
public class GatewayManageApi extends AbstractManageApi {

    /** 网关运行时，路由/配置热更新的实际操作对象。 */
    private final GatewayRuntime runtime;

    public GatewayManageApi(GatewayRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    protected String componentName() {
        return RoverComponent.GATEWAY.displayName();
    }

    @Override
    protected RuntimeConfigManager configManager() {
        return runtime.getConfigManager();
    }

    @Override
    protected String adminToken() {
        return runtime.getAdminToken();
    }

    @Override
    protected boolean dispatch(ChannelHandlerContext ctx, FullHttpRequest request, String path) {
        if (HttpMethod.GET.equals(request.method()) && ManageApiPaths.STATUS.equals(path)) {
            writeJson(ctx, HttpResponseStatus.OK, statusJson());
            return true;
        }
        if (HttpMethod.GET.equals(request.method()) && ManageApiPaths.METRICS_LIVE.equals(path)) {
            QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
            int range = ManageApiPaths.clampLiveRange(firstQuery(decoder, ManageApiPaths.PARAM_RANGE));
            writeJson(ctx, HttpResponseStatus.OK, runtime.getMetricsRegistry().liveJson(range));
            return true;
        }
        if (HttpMethod.GET.equals(request.method()) && ManageApiPaths.METRICS.equals(path)) {
            writeJson(ctx, HttpResponseStatus.OK, runtime.getMetricsRegistry().snapshotJson());
            return true;
        }
        if (HttpMethod.GET.equals(request.method()) && ManageApiPaths.METRICS_ROUTES.equals(path)) {
            QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
            String routeId = firstQuery(decoder, ManageApiPaths.PARAM_ROUTE_ID);
            int range = ManageApiPaths.clampLiveRange(firstQuery(decoder, ManageApiPaths.PARAM_RANGE));
            writeJson(ctx, HttpResponseStatus.OK,
                    runtime.getMetricsRegistry().routeMetricsJson(routeId, range));
            return true;
        }
        if (HttpMethod.GET.equals(request.method()) && ManageApiPaths.METRICS_SELFCHECK.equals(path)) {
            writeJson(ctx, HttpResponseStatus.OK, runtime.getMetricsRegistry().selfcheckJson());
            return true;
        }
        if (HttpMethod.GET.equals(request.method()) && ManageApiPaths.PROMETHEUS.equals(path)) {
            writeText(ctx, HttpResponseStatus.OK, runtime.getMetricsRegistry().prometheusText(),
                    HttpConstants.MEDIA_TYPE_PROMETHEUS);
            return true;
        }
        if (HttpMethod.GET.equals(request.method()) && ManageApiPaths.TRACES.equals(path)) {
            writeJson(ctx, HttpResponseStatus.OK, tracesJson(request));
            return true;
        }
        if (HttpMethod.GET.equals(request.method()) && ManageApiPaths.DISCOVERY_SNAPSHOT.equals(path)) {
            writeJson(ctx, HttpResponseStatus.OK, discoverySnapshotJson());
            return true;
        }
        if (ManageApiPaths.ROUTES_PREVIEW.equals(path)) {
            handleRoutesPreview(ctx, request);
            return true;
        }
        if (ManageApiPaths.ROUTES_TARGET_WEIGHT.equals(path)) {
            handleTargetWeight(ctx, request);
            return true;
        }
        if (ManageApiPaths.ROUTES_ROLLBACK.equals(path)) {
            handleRollback(ctx, request);
            return true;
        }
        if (path.startsWith(ManageApiPaths.ROUTES_OPERATIONS)) {
            handleOperationQuery(ctx, request, path);
            return true;
        }
        if (ManageApiPaths.ROUTES.equals(path)) {
            handleRoutes(ctx, request);
            return true;
        }
        return handleConfigs(ctx, request, path);
    }

    /**
     * 处理 /_manage/routes。
     *
     * <p>所有写路径都必须带 {@code revision}，没有绕过乐观锁的后门：
     * GET 路由表（含当前版本）、PUT 整表、POST 单条、DELETE 按 id 或 prefix。
     */
    private void handleRoutes(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (HttpMethod.GET.equals(request.method())) {
            writeJson(ctx, HttpResponseStatus.OK, routesStateJson());
            return;
        }
        if (HttpMethod.PUT.equals(request.method())) {
            Map<String, Object> body = JsonCodec.parseObjectMap(bodyOf(request));
            List<RouteConfig> routes = RouteOverlayStore.toRoutes(objectRows(body.get("routes")));
            GatewayRuntime.RouteChangeResult result = runtime.applyRoutes(
                    requiredRevision(body), operationId(body), routes);
            writeJson(ctx, HttpResponseStatus.OK, changeResponse(result));
            return;
        }
        if (HttpMethod.POST.equals(request.method())) {
            Map<String, Object> body = JsonCodec.parseObjectMap(bodyOf(request));
            Object routeNode = body.get("route");
            List<Map<String, Object>> rows = routeNode == null
                    ? List.of(body)
                    : objectRows(routeNode instanceof List<?> list ? list : List.of(routeNode));
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("请求体需要路由对象");
            }
            GatewayRuntime.RouteChangeResult result = runtime.addOrReplaceRoute(
                    requiredRevision(body), operationId(body), RouteOverlayStore.toRoutes(rows).get(0));
            writeJson(ctx, HttpResponseStatus.OK, changeResponse(result));
            return;
        }
        if (HttpMethod.DELETE.equals(request.method())) {
            QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
            String key = firstQuery(decoder, ManageApiPaths.PARAM_ID);
            if (key == null || key.isBlank()) {
                key = firstQuery(decoder, ManageApiPaths.PARAM_BUSINESS_PREFIX);
            }
            GatewayRuntime.RouteChangeResult result = runtime.removeRoute(
                    requiredRevision(firstQuery(decoder, ManageApiPaths.PARAM_REVISION)),
                    firstQuery(decoder, ManageApiPaths.PARAM_OPERATION_ID), key);
            writeJson(ctx, HttpResponseStatus.OK, changeResponse(result));
            return;
        }
        writeJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED,
                JsonCodec.toJson(Map.of("message", "routes 支持 GET/PUT/POST/DELETE")));
    }

    /** 处理 POST /_manage/routes/preview：只回差异与校验结果，不落盘、不生效。 */
    private void handleRoutesPreview(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!HttpMethod.POST.equals(request.method())) {
            writeJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED,
                    JsonCodec.toJson(Map.of("message", "routes/preview 只支持 POST")));
            return;
        }
        Map<String, Object> body = JsonCodec.parseObjectMap(bodyOf(request));
        List<RouteConfig> candidate = RouteOverlayStore.toRoutes(objectRows(body.get("routes")));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("revision", runtime.getRevision());
        resp.put("appliedOperationId", runtime.getAppliedOperationId());
        resp.put("message", "预览通过，未落盘、未生效");
        List<Map<String, Object>> changes = new ArrayList<>();
        for (com.rover.gateway.core.route.RouteDiff.Change change : runtime.previewRoutes(candidate)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", change.kind());
            row.put("routeId", change.routeId());
            row.put("businessPrefix", change.businessPrefix());
            row.put("detail", change.detail());
            changes.add(row);
        }
        resp.put("changes", changes);
        resp.put("changeCount", changes.size());
        writeJson(ctx, HttpResponseStatus.OK, JsonCodec.toJson(resp));
    }

    /**
     * 处理 POST /_manage/routes/targets/weight：放量/停推原语。
     *
     * <p>语义上只改一个版本的权重，内部仍走「整表 + 乐观锁」协议——
     * 读当前整表、定位目标、改权重、整表提交，因此不会和别人的并发修改互相覆盖。
     */
    private void handleTargetWeight(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!HttpMethod.POST.equals(request.method())) {
            writeJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED,
                    JsonCodec.toJson(Map.of("message", "routes/targets/weight 只支持 POST")));
            return;
        }
        Map<String, Object> body = JsonCodec.parseObjectMap(bodyOf(request));
        String routeKey = text(body.get(ManageApiPaths.PARAM_ROUTE_ID));
        String serviceName = text(body.get("serviceName"));
        String group = text(body.get("group"));
        int weight = intValue(body.get("weight"));
        if (routeKey.isBlank()) {
            throw new IllegalArgumentException("需要 routeId");
        }
        if (serviceName.isBlank()) {
            throw new IllegalArgumentException("需要 serviceName");
        }
        if (weight < 0 || weight > com.rover.gateway.core.route.RouteTarget.MAX_WEIGHT) {
            throw new IllegalArgumentException(
                    "weight 必须在 0~" + com.rover.gateway.core.route.RouteTarget.MAX_WEIGHT + " 之间");
        }
        List<RouteConfig> current = new ArrayList<>(runtime.getRouteMatcher().listRoutes());
        boolean matched = false;
        for (int i = 0; i < current.size(); i++) {
            RouteConfig route = current.get(i);
            if (!routeKey.equals(route.getId()) && !routeKey.equals(route.getBusinessPrefix())) {
                continue;
            }
            if (route.getTargets() == null || route.getTargets().isEmpty()) {
                throw new IllegalArgumentException("这条路由没有版本目标，不能用权重接口调整: " + routeKey);
            }
            List<com.rover.gateway.core.route.RouteTarget> updated = new ArrayList<>();
            for (com.rover.gateway.core.route.RouteTarget target : route.getTargets()) {
                if (target.serviceName().equals(serviceName) && text(target.group()).equals(group)) {
                    updated.add(new com.rover.gateway.core.route.RouteTarget(
                            target.serviceName(), target.group(), weight));
                    matched = true;
                } else {
                    updated.add(target);
                }
            }
            // 只在副本上改：listRoutes() 返回的元素是正在接流的活对象，改它会绕过乐观锁与落盘
            RouteConfig copy = RouteValidator.copyRoute(route);
            copy.setTargets(updated);
            current.set(i, copy);
        }
        if (!matched) {
            throw new IllegalArgumentException(
                    "未找到版本目标: route=" + routeKey + ", service=" + serviceName + ", group=" + group);
        }
        GatewayRuntime.RouteChangeResult result = runtime.applyRoutes(
                requiredRevision(body), operationId(body), current);
        writeJson(ctx, HttpResponseStatus.OK, changeResponse(result));
    }

    /** 处理 POST /_manage/routes/rollback：从最近已应用快照回滚，产生新版本。 */
    private void handleRollback(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!HttpMethod.POST.equals(request.method())) {
            writeJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED,
                    JsonCodec.toJson(Map.of("message", "routes/rollback 只支持 POST")));
            return;
        }
        Map<String, Object> body = JsonCodec.parseObjectMap(bodyOf(request));
        Object toRevision = body.get("toRevision");
        if (toRevision == null) {
            throw new IllegalArgumentException("需要 toRevision");
        }
        GatewayRuntime.RouteChangeResult result = runtime.rollback(
                requiredRevision(body), operationId(body), intValue(toRevision));
        writeJson(ctx, HttpResponseStatus.OK, changeResponse(result));
    }

    /** 处理 GET /_manage/routes/operations/{operationId}：请求超时后确认是否已执行。 */
    private void handleOperationQuery(ChannelHandlerContext ctx, FullHttpRequest request, String path) {
        if (!HttpMethod.GET.equals(request.method())) {
            writeJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED,
                    JsonCodec.toJson(Map.of("message", "operations 只支持 GET")));
            return;
        }
        String operationId = path.substring(ManageApiPaths.ROUTES_OPERATIONS.length());
        if (operationId.isBlank()) {
            throw new IllegalArgumentException("需要 operationId");
        }
        GatewayRuntime.OperationRecord record = runtime.operationOf(operationId);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("operationId", operationId);
        if (record == null) {
            // 没有记录 != 没执行：可能是操作表已淘汰，也可能是压根没提交过
            resp.put("status", "UNKNOWN");
            resp.put("message", "没有该操作的记录：可能从未提交，或已超出最近 " + GatewayRuntime.MAX_OPERATIONS + " 条记录窗口");
        } else {
            resp.put("status", record.status().name());
            resp.put("revision", record.revision());
            resp.put("message", record.message());
            resp.put("atMillis", record.atMillis());
        }
        resp.put("currentRevision", runtime.getRevision());
        writeJson(ctx, HttpResponseStatus.OK, JsonCodec.toJson(resp));
    }

    /** 变更成功/重放响应。 */
    private String changeResponse(GatewayRuntime.RouteChangeResult result) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", result.status());
        resp.put("revision", result.revision());
        resp.put("operationId", result.operationId());
        resp.put("message", result.message());
        resp.put("routeCount", result.routes().size());
        resp.put("routes", routeMaps(result.routes()));
        return JsonCodec.toJson(resp);
    }

    /** 写路径必须显式带期望版本，否则无从判断「我看到的是不是最新的」。 */
    private static int requiredRevision(Map<String, Object> body) {
        Object value = body.get(ManageApiPaths.PARAM_REVISION);
        if (value == null) {
            throw new IllegalArgumentException("缺少 revision：请先 GET /_manage/routes 取当前版本");
        }
        return intValue(value);
    }

    private static int requiredRevision(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("缺少 revision：请先 GET /_manage/routes 取当前版本");
        }
        return intValue(raw);
    }

    private static String operationId(Map<String, Object> body) {
        return text(body.get(ManageApiPaths.PARAM_OPERATION_ID));
    }

    /** null 安全转去空白字符串。 */
    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** 宽松取整：数字直接取值，字符串解析失败按 0（后续区间校验会拒绝非法值）。 */
    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(text(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String bodyOf(FullHttpRequest request) {
        return request.content().toString(StandardCharsets.UTF_8);
    }

    /** 把 JSON 值收敛成字段表列表：非数组当空，数组里的非对象元素丢弃。 */
    private static List<Map<String, Object>> objectRows(Object value) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!(value instanceof List<?> list)) {
            return rows;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> row = new LinkedHashMap<>();
                map.forEach((key, val) -> row.put(String.valueOf(key), val));
                rows.add(row);
            }
        }
        return rows;
    }

    /** 组装 GET /_manage/traces 的 JSON：支持按 traceId / path / slow 过滤。 */
    private String tracesJson(FullHttpRequest request) {
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        String traceIdFilter = firstQuery(decoder, ManageApiPaths.PARAM_TRACE_ID);
        String pathFilter = firstQuery(decoder, ManageApiPaths.PARAM_PATH);
        String slowFilter = firstQuery(decoder, ManageApiPaths.PARAM_SLOW);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("enabled", runtime.getTraceSettings().isEnabled());
        resp.put("slowThresholdMillis", runtime.getTraceSettings().getSlowThresholdMillis());
        resp.put("sampleRate", runtime.getTraceSettings().getSampleRate());
        resp.put("capacity", runtime.getTraceBuffer().capacity());
        resp.put("count", runtime.getTraceBuffer().size());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (com.rover.gateway.core.trace.RequestTrace trace : runtime.getTraceBuffer().snapshot()) {
            if (traceIdFilter != null && !traceIdFilter.isBlank()
                    && !traceIdFilter.equals(trace.getTraceId())) {
                continue;
            }
            if (pathFilter != null && !pathFilter.isBlank()
                    && trace.getPath() != null
                    && !trace.getPath().contains(pathFilter)) {
                continue;
            }
            if (slowFilter != null && !ConfigValues.FALSE.equalsIgnoreCase(slowFilter)
                    && !trace.isSlow()) {
                continue;
            }
            rows.add(traceRow(trace));
        }
        resp.put("traces", rows);
        return JsonCodec.toJson(resp);
    }

    /** 单条 trace 的 JSON 行。 */
    private Map<String, Object> traceRow(com.rover.gateway.core.trace.RequestTrace trace) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("traceId", trace.getTraceId());
        row.put("method", trace.getMethod());
        row.put("path", trace.getPath());
        row.put("routeId", nullToEmpty(trace.getRouteId()));
        row.put("targetUrl", nullToEmpty(trace.getTargetUrl()));
        row.put("statusCode", trace.getStatusCode());
        row.put("startMillis", trace.getStartMillis());
        row.put("totalCostMs", trace.getTotalCostMs());
        row.put("slow", trace.isSlow());
        List<Map<String, Object>> phases = new ArrayList<>();
        for (com.rover.gateway.core.trace.RequestTrace.Phase phase : trace.getPhases()) {
            Map<String, Object> phaseRow = new LinkedHashMap<>();
            phaseRow.put("name", phase.getName());
            phaseRow.put("costMs", phase.getCostMs());
            phases.add(phaseRow);
        }
        row.put("phases", phases);
        return row;
    }

    /**
     * 组装 GET /_manage/discovery/snapshot：网关自己观察到哪些 service@group、本地缓存版本与实例数。
     *
     * <p>这是「网关视角」的证据，用于和注册中心视角对账（如灰度验收时确认两边看到的是同一批实例）。
     * 非动态发现或没有客户端时如实返回 supported=false 并附空列表，<b>不抛异常</b>——
     * 「没有这项能力」与「读取失败」必须在调用方那里是可区分的两件事。
     */
    private String discoverySnapshotJson() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("discoveryType", runtime.getDiscoveryType().name());
        ServiceDiscovery discovery = runtime.getServiceDiscovery();
        if (!(discovery instanceof NameserverServiceDiscovery nameserver)) {
            resp.put("supported", false);
            resp.put("subscribeCount", 0);
            resp.put("subscriptions", List.of());
            return JsonCodec.toJson(resp);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (NameserverServiceDiscovery.SubscriptionView view : nameserver.subscriptions()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("serviceName", view.serviceName());
            row.put("group", nullToEmpty(view.group()));
            row.put("revision", view.revision());
            row.put("epoch", nullToEmpty(view.epoch()));
            row.put("instanceCount", view.instanceCount());
            row.put("healthyCount", view.healthyCount());
            rows.add(row);
        }
        resp.put("supported", true);
        resp.put("subscribeCount", rows.size());
        resp.put("subscriptions", rows);
        return JsonCodec.toJson(resp);
    }

    /** 组装 GET /_manage/status 的 JSON 内容。 */
    private String statusJson() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("component", RoverComponent.GATEWAY.id());
        status.put("up", true);
        status.put("port", runtime.getPort());
        status.put("discoveryType", runtime.getDiscoveryType().name());
        if (runtime.getServiceDiscovery() instanceof ServiceDiscoveryStatus discoveryStatus) {
            status.put("discovery", discoveryStatus.status());
        }
        status.put("routeCount", runtime.getRouteMatcher().listRoutes().size());
        status.put("filterEnabled", runtime.getFilterSettings().isEnabled());
        status.put("loadBalanceStrategy", runtime.getLoadBalanceStrategy().get());
        status.put("requestTimeoutMillis", runtime.getProxyClient().getRequestTimeoutMillis());
        status.put("connectTimeoutMillis", runtime.getConnectTimeoutMillis());
        status.put("maxConnectionsPerEventLoop", GatewayDefaults.intPropertyOrDefault(
                GatewaySystemProperties.MAX_CONNECTIONS_PER_EVENT_LOOP,
                GatewayDefaults.MAX_CONNECTIONS_PER_EVENT_LOOP));
        status.put("maxPendingAcquires", GatewayDefaults.intPropertyOrDefault(
                GatewaySystemProperties.MAX_PENDING_ACQUIRES,
                GatewayDefaults.MAX_PENDING_ACQUIRES));
        status.put("maxInflight", runtime.maxInflight());
        status.put("inflightUsed", runtime.inflightUsed());
        status.put("inboundIdleTimeoutSeconds", GatewayDefaults.intPropertyOrDefault(
                GatewaySystemProperties.INBOUND_IDLE_TIMEOUT_SECONDS,
                GatewayDefaults.INBOUND_IDLE_TIMEOUT_SECONDS));
        status.put("requestIdleTimeoutSeconds", GatewayDefaults.intPropertyOrDefault(
                GatewaySystemProperties.REQUEST_IDLE_TIMEOUT_SECONDS,
                GatewayDefaults.REQUEST_IDLE_TIMEOUT_SECONDS));
        status.put("outboundIdleTimeoutSeconds", GatewayDefaults.intPropertyOrDefault(
                GatewaySystemProperties.OUTBOUND_IDLE_TIMEOUT_SECONDS,
                GatewayDefaults.OUTBOUND_IDLE_TIMEOUT_SECONDS));
        return JsonCodec.toJson(status);
    }

    /**
     * 路由表 + 当前版本号。
     *
     * <p>版本号必须在路由表里一起给出：调用方拿到路由表却不知道版本，就没法安全地提交变更
     * （写路径要求 {@code revision}），只能再猜一次——那才是并发覆盖的根源。
     */
    private String routesStateJson() {
        List<RouteConfig> routes = runtime.getRouteMatcher().listRoutes();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("revision", runtime.getRevision());
        resp.put("appliedOperationId", runtime.getAppliedOperationId());
        resp.put("routeCount", routes.size());
        resp.put("routes", routeMaps(routes));
        return JsonCodec.toJson(resp);
    }

    /** 把 RouteConfig 列表转成 Map 行，供 JSON 序列化。 */
    private List<Map<String, Object>> routeMaps(List<RouteConfig> routes) {
        return RouteOverlayStore.toRows(routes);
    }

    /** 取 query 参数第一个值。 */
    private static String firstQuery(QueryStringDecoder decoder, String name) {
        List<String> values = decoder.parameters().get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }
}
