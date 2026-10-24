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
import com.rover.common.manage.ManageApiException;
import com.rover.gateway.core.config.GatewayDefaults;
import com.rover.gateway.core.config.GatewaySystemProperties;
import com.rover.gateway.core.discovery.NameserverServiceDiscovery;
import com.rover.gateway.core.route.RouteConfig;
import com.rover.gateway.core.route.RouteOverlayStore;
import com.rover.gateway.core.route.RouteInstanceIsolationStore;
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

    /** 整表提交里承载路由列表的字段名。 */
    private static final String ROUTES_FIELD = "routes";
    /** Gateway authenticates the Admin service token; human approval identity lives in Admin audit. */
    private static final String CONFIG_ACTION_PRINCIPAL = "rover-admin";

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
            String from = firstQuery(decoder, "fromMillis"), to = firstQuery(decoder, "toMillis");
            if (from != null || to != null) {
                try {
                    if (from == null || to == null || routeId == null || routeId.isBlank()) { throw new IllegalArgumentException(); }
                    writeJson(ctx, HttpResponseStatus.OK,
                            runtime.getMetricsRegistry().routeWindowJson(routeId, Long.parseLong(from), Long.parseLong(to)));
                } catch (IllegalArgumentException ex) {
                    writeJson(ctx, HttpResponseStatus.BAD_REQUEST, "{\"error\":\"invalid final-response window\"}");
                }
                return true;
            }
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
        if (ManageApiPaths.ROUTE_INSTANCE_ISOLATIONS.equals(path)) {
            handleInstanceIsolations(ctx, request);
            return true;
        }
        if (path.equals(ManageApiPaths.CONFIG_ACTIONS) || path.startsWith(ManageApiPaths.CONFIG_ACTIONS + "/")) {
            handleConfigActions(ctx, request, path);
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

    private void handleConfigActions(ChannelHandlerContext ctx, FullHttpRequest request, String path) {
        if (HttpMethod.POST.equals(request.method()) && ManageApiPaths.CONFIG_ACTIONS.equals(path)) {
            Map<String,Object> body = JsonCodec.parseObjectMap(bodyOf(request));
            com.rover.gateway.core.config.GatewayConfigActionStore.Receipt receipt;
            try {
                receipt = runtime.getConfigActionStore().execute(text(body.get("operationId")),
                        text(body.get("key")), text(body.get("expectedValue")),
                        text(body.get("desiredValue")), CONFIG_ACTION_PRINCIPAL);
            } catch (com.rover.gateway.core.config.GatewayConfigActionStore.IdempotencyConflictException ex) {
                throw new ManageApiException(HttpResponseStatus.CONFLICT, ex.getMessage());
            }
            writeJson(ctx, HttpResponseStatus.OK, JsonCodec.toJson(configActionReceipt(receipt)));
            return;
        }
        if (HttpMethod.GET.equals(request.method()) && path.startsWith(ManageApiPaths.CONFIG_ACTIONS + "/")) {
            String operationId = path.substring((ManageApiPaths.CONFIG_ACTIONS + "/").length());
            var receipt = runtime.getConfigActionStore().query(operationId);
            if (receipt == null) {
                writeJson(ctx, HttpResponseStatus.NOT_FOUND, JsonCodec.toJson(Map.of("operationId", operationId, "status", "UNKNOWN")));
            } else { writeJson(ctx, HttpResponseStatus.OK, JsonCodec.toJson(configActionReceipt(receipt))); }
            return;
        }
        writeJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, JsonCodec.toJson(Map.of("message", "config actions support POST collection and GET receipt")));
    }

    private static Map<String,Object> configActionReceipt(com.rover.gateway.core.config.GatewayConfigActionStore.Receipt receipt) {
        Map<String,Object> body = new LinkedHashMap<>(receipt.result());
        body.put("operationId", receipt.operationId()); body.put("key", receipt.configKey());
        body.put("actor", receipt.actor()); body.put("status", receipt.status());
        body.put("replayed", receipt.replayed());
        body.put("expectedValue", receipt.expectedValue());
        body.put("desiredValue", receipt.desiredValue()); body.put("createdAtMillis", receipt.createdAtMillis());
        body.put("updatedAtMillis", receipt.updatedAtMillis());
        // The current shared Admin token authenticates the caller, not this caller-supplied name.
        body.put("actorAuthenticated", false);
        return body;
    }

    /**
     * 处理 /_manage/routes。
     *
     * 所有写路径都必须带 {@code revision}，没有绕过乐观锁的后门：
     * GET 路由表（含当前版本）、PUT 整表、POST 单条、DELETE 按 id 或 prefix。
     */
    private void handleRoutes(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (HttpMethod.GET.equals(request.method())) {
            writeJson(ctx, HttpResponseStatus.OK, routesStateJson());
            return;
        }
        if (HttpMethod.PUT.equals(request.method())) {
            Map<String, Object> body = JsonCodec.parseObjectMap(bodyOf(request));
            List<RouteConfig> routes = strictRoutes(body);
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
        List<RouteConfig> candidate = strictRoutes(body);
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
     * 语义上只改一个版本的权重，内部仍走「整表 + 乐观锁」协议——
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
        GatewayRuntime.RouteChangeResult result = runtime.adjustTargetWeight(
                requiredRevision(body), operationId(body), routeKey, serviceName, group, weight,
                text(body.get("expectedResourceUid")), body.containsKey("expectedGeneration")
                        ? intValue(body.get("expectedGeneration")) : 0);
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
            resp.put("message", "没有可证明的持久回执：可能从未提交或来自旧版本；UNKNOWN 不表示没有执行，禁止换号补做");
        } else {
            resp.put("status", record.status().name());
            resp.put("revision", record.revision());
            resp.put("message", record.message());
            resp.put("atMillis", record.atMillis());
            resp.put("requestHash", record.requestHash());
            resp.put("expiresAtMillis", record.expiresAtMillis());
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

    /** 写入参数必须为精确整数；不能把缺失/非法输入变成合法的停流权重 0。 */
    private static int intValue(Object value) {
        try {
            return new java.math.BigDecimal(text(value)).intValueExact();
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new IllegalArgumentException("需要 32 位精确整数: " + text(value));
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

    /**
     * 严格解析整表提交里的 {@code routes} 字段。
     *
     * 这里刻意不做宽松收敛。字段缺失或拼错键名时若当成「空表」，逐条校验会全部通过，
     * 于是「请求体写错了」变成「清空整张路由表并落盘」——线上全站 404，重启也回不来。
     * 只有显式写 {@code []} 才算「确实要把路由表清空」。
     *
     * @param body 请求体
     * @return 解析后的路由列表
     * @throws IllegalArgumentException 字段缺失、不是数组或元素不是对象
     */
    private static List<RouteConfig> strictRoutes(Map<String, Object> body) {
        if (!body.containsKey(ROUTES_FIELD)) {
            throw new IllegalArgumentException(
                    "请求体缺少 " + ROUTES_FIELD + " 字段：整表提交必须显式给出 " + ROUTES_FIELD
                            + " 数组，清空路由表请传 []");
        }
        Object value = body.get(ROUTES_FIELD);
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(ROUTES_FIELD + " 必须是数组");
        }
        List<Map<String, Object>> rows = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException(ROUTES_FIELD + " 的元素必须是路由对象");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            map.forEach((key, val) -> row.put(String.valueOf(key), val));
            rows.add(row);
        }
        return RouteOverlayStore.toRoutes(rows);
    }

    /** 组装 GET /_manage/traces 的 JSON：支持按 traceId / path / slow / error 过滤。 */
    private String tracesJson(FullHttpRequest request) {
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        String traceIdFilter = firstQuery(decoder, ManageApiPaths.PARAM_TRACE_ID);
        String pathFilter = firstQuery(decoder, ManageApiPaths.PARAM_PATH);
        String slowFilter = firstQuery(decoder, ManageApiPaths.PARAM_SLOW);
        String errorFilter = firstQuery(decoder, ManageApiPaths.PARAM_ERROR);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("enabled", runtime.getTraceSettings().isEnabled());
        resp.put("slowThresholdMillis", runtime.getTraceSettings().getSlowThresholdMillis());
        resp.put("sampleRate", runtime.getTraceSettings().getSampleRate());
        resp.put("capacity", runtime.getTraceBuffer().capacity());
        resp.put("count", runtime.getTraceBuffer().size());
        resp.put("observedAtMillis", System.currentTimeMillis());
        resp.put("collectionStartedAtMillis", runtime.getTraceBuffer().startedAtMillis());
        resp.put("overwritten", runtime.getTraceBuffer().overwritten());
        resp.put("samplingMode", "RANDOM_PLUS_SLOW");

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
            if (errorFilter != null && !ConfigValues.FALSE.equalsIgnoreCase(errorFilter)
                    && trace.getStatusCode() < 500) {
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
        row.put("observation", trace.getObservation());
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
     * 组装网关发现快照，包含服务分组、缓存版本与实例数。
     * 非动态发现或无客户端时返回 supported=false 和空列表。
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

    private synchronized void handleInstanceIsolations(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (HttpMethod.GET.equals(request.method())) {
            List<RouteInstanceIsolationStore.Isolation> allRows = runtime.getInstanceIsolationStore().list();
            int offset = 0, limit = 1000;
            QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
            try { if (decoder.parameters().containsKey("offset")) { offset = Math.max(0, Integer.parseInt(decoder.parameters().get("offset").get(0))); }
                if (decoder.parameters().containsKey("limit")) { limit = Math.max(1, Math.min(1000, Integer.parseInt(decoder.parameters().get("limit").get(0)))); } }
            catch (RuntimeException ex) { writeJson(ctx, HttpResponseStatus.BAD_REQUEST, JsonCodec.toJson(Map.of("message", "offset/limit 必须为整数"))); return; }
            int from = Math.min(offset, allRows.size()), to = Math.min(allRows.size(), from + limit);
            List<Map<String,Object>> rows = allRows.subList(from, to).stream().map(row -> {
                Map<String,Object> value = new LinkedHashMap<>();
                value.put("routeUid", row.key().routeUid()); value.put("generation", row.key().generation());
                value.put("routeId", row.key().routeId()); value.put("serviceName", row.key().serviceName());
                value.put("group", row.key().group()); value.put("instanceId", row.key().instanceId());
                value.put("host", row.key().host()); value.put("port", row.key().port());
                value.put("source", row.source().name()); value.put("state", row.state().name());
                value.put("lifecycle", row.lifecycle(System.currentTimeMillis())); value.put("requestedBy", row.requestedBy());
                value.put("reason", row.reason()); value.put("capacityEvidence", row.capacityEvidence());
                value.put("minimumHealthyAfter", row.minimumHealthyAfter()); value.put("createdAtMillis", row.createdAtMillis());
                value.put("expiresAtMillis", row.expiresAtMillis()); value.put("version", row.version()); return value;
            }).toList();
            writeJson(ctx, HttpResponseStatus.OK, JsonCodec.toJson(Map.of("isolations", rows, "total", allRows.size(),
                    "offset", offset, "limit", limit, "hasMore", to < allRows.size(), "durable", runtime.getInstanceIsolationStore().healthy()))); return;
        }
        if (!HttpMethod.POST.equals(request.method())) {
            writeJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, JsonCodec.toJson(Map.of("message", "只支持 GET/POST"))); return;
        }
        runtime.withRouteMutationLock(() -> {
        Map<String,Object> body = JsonCodec.parseObjectMap(bodyOf(request));
        RouteInstanceIsolationStore.Key key = isolationKey(body);
        String operation = requiredOperation(body);
        long now = System.currentTimeMillis();
        boolean restore = "RESTORE".equals(text(body.get("operation")));
        RouteInstanceIsolationStore.Source source = RouteInstanceIsolationStore.Source.valueOf(text(body.get("source")));
        int expectedVersion = intValue(body.get("expectedVersion"));
        RouteInstanceIsolationStore.Isolation prior = restore
                ? runtime.getInstanceIsolationStore().replayRestore(key, source, text(body.get("requestedBy")), expectedVersion,
                        text(body.get("capacityEvidence")), intValue(body.get("minimumHealthyAfter")), operation)
                : runtime.getInstanceIsolationStore().replayIsolate(key, source, text(body.get("requestedBy")),
                        text(body.get("reason")), text(body.get("capacityEvidence")), intValue(body.get("minimumHealthyAfter")),
                        longValue(body.get("expiresAtMillis")), expectedVersion, operation);
        if (prior != null) {
            writeJson(ctx, HttpResponseStatus.OK, JsonCodec.toJson(Map.of("status", prior.state().name(),
                    "version", prior.version(), "operationId", operation, "replayed", true)));
            return;
        }
        RouteInstanceIsolationStore.Isolation existing = runtime.getInstanceIsolationStore().list().stream()
                .filter(row -> row.key().equals(key)).findFirst().orElse(null);
        RouteInstanceIsolationStore.Isolation result;
        if (restore) {
            result = runtime.getInstanceIsolationStore().restore(key, source, text(body.get("requestedBy")),
                    expectedVersion, text(body.get("capacityEvidence")), intValue(body.get("minimumHealthyAfter")), operation, now,
                    () -> { validateIsolationTarget(key, true); requireRestoreCapacity(body, key, existing); });
        } else if ("ISOLATE".equals(text(body.get("operation")))) {
            result = runtime.getInstanceIsolationStore().isolate(key, source, text(body.get("requestedBy")),
                    text(body.get("reason")), text(body.get("capacityEvidence")), intValue(body.get("minimumHealthyAfter")),
                    longValue(body.get("expiresAtMillis")), expectedVersion, operation, now,
                    () -> { validateIsolationTarget(key, false); requireRemainingCapacity(key, existing, body); });
        } else { throw new IllegalArgumentException("operation 必须是 ISOLATE 或 RESTORE"); }
        writeJson(ctx, HttpResponseStatus.OK, JsonCodec.toJson(Map.of("status", result.state().name(),
                "version", result.version(), "operationId", operation, "isolation", Map.of(
                        "routeUid", key.routeUid(), "generation", key.generation(), "instanceId", key.instanceId(),
                        "source", result.source().name(), "lifecycle", result.lifecycle(now)))));
        });
    }

    private void validateIsolationTarget(RouteInstanceIsolationStore.Key key, boolean restoring) {
        RouteConfig route = runtime.getRouteMatcher().listRoutes().stream().filter(item ->
                key.routeUid().equals(item.getResourceUid()) && key.generation() == item.getGeneration()
                        && key.routeId().equals(item.getId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("路由 UID/代次已改变，旧隔离不得执行"));
        var target = route.getTargets() == null ? null : route.getTargets().stream().filter(item ->
                key.serviceName().equals(item.serviceName()) && (item.group() == null || item.group().isBlank()
                        || key.group().equals(item.group()))).findFirst().orElse(null);
        if (runtime.getDiscoveryType() != com.rover.gateway.core.discovery.DiscoveryType.NAMESERVER || target == null) {
            throw new IllegalArgumentException("实例隔离当前仅支持 Nameserver 动态服务路由");
        }
        var allRows = runtime.getInstanceIsolationStore().list().stream().filter(row -> row.key().routeUid().equals(key.routeUid())
                && row.key().generation() == key.generation() && row.key().routeId().equals(key.routeId())
                && row.key().serviceName().equals(key.serviceName())).toList();
        if (!restoring) {
            var rows = allRows;
            if (rows.stream().anyMatch(row -> row.key().instanceId().equals(key.instanceId())
                    && row.key().host().equals(key.host()) && row.key().port() == key.port()
                    && row.active())) {
                throw new IllegalArgumentException("该实例已处于隔离状态，请核验当前记录");
            }
        }
        if (runtime.getServiceDiscovery() instanceof NameserverServiceDiscovery nameserver) {
            String discoveryGroup = target.group() == null || target.group().isBlank() ? null : target.group();
            List<com.rover.common.model.ServiceInstance> instances = nameserver.getInstanceCache().get(key.serviceName(), discoveryGroup);
            boolean found = instances.stream().anyMatch(instance -> {
                String id = instance.getInstanceId() == null || instance.getInstanceId().isBlank()
                        ? instance.getHost() + ":" + instance.getPort() : instance.getInstanceId();
                return id.equals(key.instanceId()) && instance.getHost().equals(key.host()) && instance.getPort() == key.port()
                        && (instance.getGroup() == null ? "" : instance.getGroup()).equals(key.group())
                        && (!restoring || instance.isHealthy());
            });
            if (!found) { throw new IllegalArgumentException(restoring
                    ? "实例身份不匹配或实例仍不健康，拒绝恢复流量" : "实例身份与 Gateway 当前发现快照不匹配"); }
        } else { throw new IllegalArgumentException("实例隔离当前仅支持 Nameserver 动态服务路由"); }
    }

    private void requireRestoreCapacity(Map<String,Object> body, RouteInstanceIsolationStore.Key key,
                                        RouteInstanceIsolationStore.Isolation existing) {
        int minimum = intValue(body.get("minimumHealthyAfter"));
        String evidence = text(body.get("capacityEvidence"));
        if (minimum < 1 || evidence.isBlank() || evidence.length() > 256) {
            throw new IllegalArgumentException("恢复必须声明剩余容量证据及最少健康实例数");
        }
        if (existing == null || !existing.active()) { throw new IllegalArgumentException("该实例当前没有有效隔离记录"); }
        RouteConfig route = runtime.getRouteMatcher().listRoutes().stream().filter(item -> key.routeUid().equals(item.getResourceUid())
                && key.generation() == item.getGeneration() && key.routeId().equals(item.getId())).findFirst().orElseThrow();
        List<com.rover.common.model.ServiceInstance> instances = capacityInstances(route, key);
        var isolatedKeys = runtime.getInstanceIsolationStore().active(key.routeUid(), key.generation(), key.routeId(), key.serviceName(), "")
                .stream().map(RouteInstanceIsolationStore.Isolation::key).collect(java.util.stream.Collectors.toSet());
        long healthyAfter = instances.stream().filter(instance -> {
            RouteInstanceIsolationStore.Key instanceKey = isolationKey(route, key.serviceName(), instance);
            return instanceKey.equals(key) || !isolatedKeys.contains(instanceKey);
        }).count();
        if (healthyAfter < minimum) {
            throw new IllegalArgumentException("恢复后剩余容量门槛不满足；核对实例健康与隔离状态后再试");
        }
    }

    private void requireRemainingCapacity(RouteInstanceIsolationStore.Key key,
            RouteInstanceIsolationStore.Isolation existing, Map<String,Object> body) {
        String evidence = text(body.get("capacityEvidence"));
        int minimum = intValue(body.get("minimumHealthyAfter"));
        if (evidence.isBlank() || evidence.length() > 256 || minimum < 1) {
            throw new IllegalArgumentException("隔离前必须提供容量核对记录及隔离后的最少健康实例数");
        }
        if (existing != null && existing.active()) { return; }
        RouteConfig route = runtime.getRouteMatcher().listRoutes().stream().filter(item -> key.routeUid().equals(item.getResourceUid())
                && key.generation() == item.getGeneration() && key.routeId().equals(item.getId())).findFirst().orElseThrow();
        List<com.rover.common.model.ServiceInstance> instances = capacityInstances(route, key);
        var isolatedKeys = runtime.getInstanceIsolationStore().active(key.routeUid(), key.generation(), key.routeId(), key.serviceName(), "")
                .stream().map(RouteInstanceIsolationStore.Isolation::key).collect(java.util.stream.Collectors.toSet());
        long remaining = instances.stream().map(instance -> isolationKey(route, key.serviceName(), instance))
                .filter(instanceKey -> !instanceKey.equals(key) && !isolatedKeys.contains(instanceKey)).count();
        if (remaining < minimum) {
            throw new IllegalArgumentException("隔离会使剩余健康实例低于门槛；先扩容或降低经核准的门槛");
        }
    }

    private List<com.rover.common.model.ServiceInstance> capacityInstances(RouteConfig route, RouteInstanceIsolationStore.Key key) {
        if (!(runtime.getServiceDiscovery() instanceof NameserverServiceDiscovery nameserver)) { return List.of(); }
        boolean allGroups = route.getTargets().stream().anyMatch(target -> key.serviceName().equals(target.serviceName())
                && (target.group() == null || target.group().isBlank()));
        String group = allGroups ? null : key.group();
        return nameserver.getInstanceCache().get(key.serviceName(), group).stream()
                .filter(com.rover.common.model.ServiceInstance::isHealthy).toList();
    }

    private static RouteInstanceIsolationStore.Key isolationKey(RouteConfig route, String serviceName,
            com.rover.common.model.ServiceInstance instance) {
        String instanceId = instance.getInstanceId() == null || instance.getInstanceId().isBlank()
                ? instance.getHost() + ":" + instance.getPort() : instance.getInstanceId();
        return new RouteInstanceIsolationStore.Key(route.getResourceUid(), route.getGeneration(), route.getId(),
                serviceName, instance.getGroup(), instanceId, instance.getHost(), instance.getPort());
    }

    private static RouteInstanceIsolationStore.Key isolationKey(Map<String,Object> body) {
        return new RouteInstanceIsolationStore.Key(text(body.get("routeUid")), longValue(body.get("generation")),
                text(body.get("routeId")), text(body.get("serviceName")), text(body.get("group")), text(body.get("instanceId")),
                text(body.get("host")), intValue(body.get("port")));
    }
    private static String requiredOperation(Map<String,Object> body) {
        String operation = text(body.get("operation"));
        String operationId = text(body.get("operationId"));
        if (!("ISOLATE".equals(operation) || "RESTORE".equals(operation))) {
            throw new IllegalArgumentException("operation 必须是 ISOLATE 或 RESTORE");
        }
        if (operationId.isBlank() || operationId.length() > 128
                || !operationId.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("需要有效的 operationId");
        }
        return operationId;
    }
    private static long longValue(Object value) {
        try { return new java.math.BigDecimal(text(value)).longValueExact(); }
        catch (NumberFormatException | ArithmeticException ex) { throw new IllegalArgumentException("需要精确长整数"); }
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
     * 版本号必须在路由表里一起给出：调用方拿到路由表却不知道版本，就没法安全地提交变更
     * （写路径要求 {@code revision}），只能再猜一次——那才是并发覆盖的根源。
     */
    private String routesStateJson() {
        List<RouteConfig> routes = runtime.getRouteMatcher().listRoutes();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("revision", runtime.getRevision());
        resp.put("appliedOperationId", runtime.getAppliedOperationId());
        resp.put("routeCount", routes.size());
        resp.put("operationReceiptRetentionMillis", com.rover.gateway.core.route.RouteOperationStore.RECEIPT_RETENTION_MILLIS);
        resp.put("routes", routeMaps(routes));
        return JsonCodec.toJson(resp);
    }

    /** 把 RouteConfig 列表转成 Map 行，供 JSON 序列化。 */
    private List<Map<String, Object>> routeMaps(List<RouteConfig> routes) {
        return RouteOverlayStore.toResourceRows(routes);
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
