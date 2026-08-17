package com.rover.gateway.core.manage;

import com.rover.common.config.RuntimeConfigManager;
import com.rover.common.json.JsonCodec;
import com.rover.common.manage.AbstractManageApi;
import com.rover.gateway.core.route.RouteConfig;
import com.rover.gateway.core.route.RouteOverlayStore;
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
        return "Gateway";
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
        if (HttpMethod.GET.equals(request.method()) && (PREFIX + "/status").equals(path)) {
            writeJson(ctx, HttpResponseStatus.OK, statusJson());
            return true;
        }
        if (HttpMethod.GET.equals(request.method()) && (PREFIX + "/metrics").equals(path)) {
            writeJson(ctx, HttpResponseStatus.OK, runtime.getMetricsRegistry().snapshotJson());
            return true;
        }
        if (HttpMethod.GET.equals(request.method()) && (PREFIX + "/metrics/selfcheck").equals(path)) {
            writeJson(ctx, HttpResponseStatus.OK, runtime.getMetricsRegistry().selfcheckJson());
            return true;
        }
        if (HttpMethod.GET.equals(request.method()) && (PREFIX + "/prometheus").equals(path)) {
            writeText(ctx, HttpResponseStatus.OK, runtime.getMetricsRegistry().prometheusText(),
                    "text/plain; version=0.0.4; charset=UTF-8");
            return true;
        }
        if (HttpMethod.GET.equals(request.method()) && (PREFIX + "/traces").equals(path)) {
            writeJson(ctx, HttpResponseStatus.OK, tracesJson(request));
            return true;
        }
        if ((PREFIX + "/routes").equals(path)) {
            handleRoutes(ctx, request);
            return true;
        }
        return handleConfigs(ctx, request, path);
    }

    /** 处理 /_manage/routes：GET 列表、PUT 整表、POST 单条、DELETE 按 id 或 prefix。 */
    private void handleRoutes(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (HttpMethod.GET.equals(request.method())) {
            writeJson(ctx, HttpResponseStatus.OK, routesJson(runtime.getRouteMatcher().listRoutes()));
            return;
        }
        if (HttpMethod.PUT.equals(request.method())) {
            String body = request.content().toString(StandardCharsets.UTF_8);
            List<RouteConfig> routes = RouteOverlayStore.toRoutes(JsonCodec.parseStringMapArray(body));
            List<RouteConfig> applied = runtime.applyRoutes(routes);
            writeJson(ctx, HttpResponseStatus.OK, routesApplyResponse(applied, "路由已热更新并落盘"));
            return;
        }
        if (HttpMethod.POST.equals(request.method())) {
            String body = request.content().toString(StandardCharsets.UTF_8);
            List<Map<String, String>> rows = JsonCodec.parseStringMapArray(
                    body.trim().startsWith("[") ? body : "[" + body + "]");
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("请求体需要路由对象");
            }
            RouteConfig route = RouteOverlayStore.toRoutes(rows).get(0);
            List<RouteConfig> applied = runtime.addOrReplaceRoute(route);
            writeJson(ctx, HttpResponseStatus.OK, routesApplyResponse(applied, "路由已新增/更新并热生效"));
            return;
        }
        if (HttpMethod.DELETE.equals(request.method())) {
            QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
            String key = firstQuery(decoder, "id");
            if (key == null || key.isBlank()) {
                key = firstQuery(decoder, "businessPrefix");
            }
            List<RouteConfig> applied = runtime.removeRoute(key);
            writeJson(ctx, HttpResponseStatus.OK, routesApplyResponse(applied, "路由已删除并热生效"));
            return;
        }
        writeJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED,
                JsonCodec.toJson(Map.of("message", "routes 支持 GET/PUT/POST/DELETE")));
    }

    /** 组装 GET /_manage/traces 的 JSON：支持按 traceId / path / slow 过滤。 */
    private String tracesJson(FullHttpRequest request) {
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        String traceIdFilter = firstQuery(decoder, "traceId");
        String pathFilter = firstQuery(decoder, "path");
        String slowFilter = firstQuery(decoder, "slow");

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
            if (slowFilter != null && !"false".equalsIgnoreCase(slowFilter)
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

    /** 组装 GET /_manage/status 的 JSON 内容。 */
    private String statusJson() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("component", "gateway");
        status.put("up", true);
        status.put("port", runtime.getPort());
        status.put("discoveryType", runtime.getDiscoveryType().name());
        status.put("routeCount", runtime.getRouteMatcher().listRoutes().size());
        status.put("filterEnabled", runtime.getFilterSettings().isEnabled());
        status.put("loadBalanceStrategy", runtime.getLoadBalanceStrategy().get());
        status.put("requestTimeoutMillis", runtime.getProxyClient().getRequestTimeoutMillis());
        status.put("connectTimeoutMillis", runtime.getConnectTimeoutMillis());
        return JsonCodec.toJson(status);
    }

    /** 路由变更成功响应 JSON。 */
    private String routesApplyResponse(List<RouteConfig> routes, String message) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("message", message);
        resp.put("routeCount", routes.size());
        resp.put("routes", routeMaps(routes));
        return JsonCodec.toJson(resp);
    }

    /** 路由列表 JSON 数组。 */
    private String routesJson(List<RouteConfig> routes) {
        return JsonCodec.toJson(routeMaps(routes));
    }

    /** 把 RouteConfig 列表转成 Map 行，供 JSON 序列化。 */
    private List<Map<String, Object>> routeMaps(List<RouteConfig> routes) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RouteConfig route : routes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", nullToEmpty(route.getId()));
            row.put("businessPrefix", nullToEmpty(route.getBusinessPrefix()));
            row.put("targetUrl", nullToEmpty(route.getTargetUrl()));
            row.put(
                    "targetUrls",
                    route.getTargetUrls() == null || route.getTargetUrls().isEmpty()
                            ? ""
                            : String.join(",", route.getTargetUrls()));
            row.put("serviceName", nullToEmpty(route.getServiceName()));
            row.put("group", nullToEmpty(route.getGroup()));
            row.put("stripPrefix", nullToEmpty(route.getStripPrefix()));
            rows.add(row);
        }
        return rows;
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
