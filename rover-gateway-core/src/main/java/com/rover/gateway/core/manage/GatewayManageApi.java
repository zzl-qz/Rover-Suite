package com.rover.gateway.core.manage;

import com.rover.common.config.ConfigApplyMode;
import com.rover.common.config.ConfigChangeEvent;
import com.rover.common.config.ConfigItem;
import com.rover.common.json.ManageJson;
import com.rover.gateway.core.route.RouteConfig;
import com.rover.gateway.core.route.RouteOverlayStore;
import com.rover.gateway.core.runtime.GatewayRuntime;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:40:00
 * Description: Gateway 同口管理 API（/_manage/**）
 *
 * 这个类是什么：Gateway 内置的管理 HTTP 接口，与业务流量共用端口。
 * 核心职责：提供 status、routes、configs 等 REST 风格端点；
 * 路由和配置变更委托 GatewayRuntime 热更新并落盘 overlay。
 * 被谁用：GatewayHttpServerHandler 在 /_manage 前缀请求时短路调用。
 */
@Slf4j
public class GatewayManageApi {

    /** 管理 API 路径前缀。 */
    public static final String PREFIX = "/_manage";

    /** 网关运行时，路由/配置热更新的实际操作对象。 */
    private final GatewayRuntime runtime;

    /**
     * @param runtime 网关运行时
     */
    public GatewayManageApi(GatewayRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * 判断路径是否属于管理 API。
     *
     * @param path 请求路径（不含 query）
     * @return true 表示走管理口
     */
    public boolean supports(String path) {
        return path != null && path.startsWith(PREFIX);
    }

    /**
     * 分发管理请求到具体端点，统一 JSON 响应。
     *
     * @param ctx     Netty 通道上下文
     * @param request HTTP 请求
     * @param path    请求路径
     */
    public void handle(ChannelHandlerContext ctx, FullHttpRequest request, String path) {
        try {
            if (HttpMethod.GET.equals(request.method()) && (PREFIX + "/status").equals(path)) {
                writeJson(ctx, HttpResponseStatus.OK, statusJson());
                return;
            }
            if ((PREFIX + "/routes").equals(path)) {
                handleRoutes(ctx, request);
                return;
            }
            if (HttpMethod.GET.equals(request.method()) && (PREFIX + "/configs").equals(path)) {
                writeJson(ctx, HttpResponseStatus.OK,
                        ManageJson.configItems(runtime.getConfigManager().listConfigs()));
                return;
            }
            if (HttpMethod.POST.equals(request.method()) && (PREFIX + "/configs").equals(path)) {
                handleUpdateConfig(ctx, request);
                return;
            }
            writeJson(ctx, HttpResponseStatus.NOT_FOUND,
                    ManageJson.object(Map.of("message", "unknown manage path: " + path)));
        } catch (IllegalArgumentException | UnsupportedOperationException ex) {
            writeJson(ctx, HttpResponseStatus.BAD_REQUEST,
                    ManageJson.object(Map.of("message", ex.getMessage() == null ? "bad request" : ex.getMessage())));
        } catch (Exception ex) {
            log.warn("Gateway manage API error, path={}", path, ex);
            writeJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    ManageJson.object(Map.of("message", "manage api error")));
        }
    }

    /** 处理 /_manage/routes：GET 列表、PUT 整表、POST 单条、DELETE 按 id 或 prefix。 */
    private void handleRoutes(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (HttpMethod.GET.equals(request.method())) {
            writeJson(ctx, HttpResponseStatus.OK, routesJson(runtime.getRouteMatcher().listRoutes()));
            return;
        }
        if (HttpMethod.PUT.equals(request.method())) {
            String body = request.content().toString(StandardCharsets.UTF_8);
            List<RouteConfig> routes = RouteOverlayStore.toRoutes(ManageJson.parseObjectArray(body));
            List<RouteConfig> applied = runtime.applyRoutes(routes);
            writeJson(ctx, HttpResponseStatus.OK, routesApplyResponse(applied, "路由已热更新并落盘"));
            return;
        }
        if (HttpMethod.POST.equals(request.method())) {
            String body = request.content().toString(StandardCharsets.UTF_8);
            List<Map<String, String>> rows = ManageJson.parseObjectArray(
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
                ManageJson.object(Map.of("message", "routes 支持 GET/PUT/POST/DELETE")));
    }

    /** 处理 POST /_manage/configs，热更新单个配置项。 */
    private void handleUpdateConfig(ChannelHandlerContext ctx, FullHttpRequest request) {
        String body = request.content().toString(StandardCharsets.UTF_8);
        String contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE);
        String[] kv = ManageJson.parseKeyValue(body, contentType);
        if (kv == null) {
            throw new IllegalArgumentException("请求体需要 key/value");
        }
        ConfigChangeEvent event = runtime.getConfigManager().updateConfig(kv[0], kv[1]);
        ConfigItem item = null;
        for (ConfigItem candidate : runtime.getConfigManager().listConfigs()) {
            if (kv[0].equals(candidate.getKey())) {
                item = candidate;
                break;
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("key", event.getKey());
        resp.put("value", event.getNewValue());
        resp.put("oldValue", event.getOldValue());
        resp.put("applyMode", event.getApplyMode() == null ? "" : event.getApplyMode().name());
        resp.put("message", ConfigApplyMode.HOT_RELOAD.equals(event.getApplyMode())
                ? "已热更新并落盘"
                : "已保存，重启后生效");
        if (item != null) {
            resp.put("description", item.getDescription());
            resp.put("hotReloadable", item.isHotReloadable());
        }
        writeJson(ctx, HttpResponseStatus.OK, ManageJson.object(resp));
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
        status.put("routeOverlay", runtime.getRouteOverlayStore().getPath().toString());
        status.put("configOverlay", runtime.getConfigManager().getOverlayStore().getPath().toString());
        return ManageJson.object(status);
    }

    /** 路由变更成功响应 JSON。 */
    private String routesApplyResponse(List<RouteConfig> routes, String message) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("message", message);
        resp.put("routeCount", routes.size());
        resp.put("overlay", runtime.getRouteOverlayStore().getPath().toString());
        resp.put("routes", routeMaps(routes));
        return ManageJson.object(resp);
    }

    /** 路由列表 JSON 数组。 */
    private String routesJson(List<RouteConfig> routes) {
        return ManageJson.arrayOfObjects(routeMaps(routes));
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

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 写 JSON 响应到客户端。 */
    private static void writeJson(ChannelHandlerContext ctx, HttpResponseStatus status, String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        ctx.writeAndFlush(response);
    }
}
