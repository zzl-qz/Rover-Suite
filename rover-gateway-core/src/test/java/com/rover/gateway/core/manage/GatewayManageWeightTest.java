package com.rover.gateway.core.manage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.rover.common.config.RuntimeConfigOverlayStore;
import com.rover.common.constants.ManageApiPaths;
import com.rover.common.json.JsonCodec;
import com.rover.gateway.core.config.GatewayRuntimeConfigApplier;
import com.rover.gateway.core.config.GatewayRuntimeConfigManager;
import com.rover.gateway.core.discovery.DiscoverySettings;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.gateway.core.filter.FilterSettings;
import com.rover.gateway.core.route.RouteConfig;
import com.rover.gateway.core.route.RouteOverlayStore;
import com.rover.gateway.core.route.RouteTarget;
import com.rover.gateway.core.runtime.GatewayRuntime;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * /_manage/routes/targets/weight 契约测试：放量/停推只改副本。
 *
 * <p>{@code RouteMatcher.listRoutes()} 返回的列表是副本、**元素却是正在接流的活对象**。
 * 若在提交前就地改这些元素，就会绕过乐观锁与落盘直接影响线上分流——
 * 冲突或校验失败时尤其危险：请求被拒了，流量却已经切了。
 * 本测试用「请求被拒后活对象权重不变」把这条守住。
 */
class GatewayManageWeightTest {

    @TempDir
    Path tempDir;

    @Test
    void weightIncreaseAppliesAndBumpsRevision() {
        RouteConfig route = grayRoute(95, 5);
        GatewayRuntime runtime = runtime(List.of(route));

        Response response = weight(runtime, 0, "op-1", 20);

        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals("APPLIED", response.body().get("status"));
        assertEquals(1, ((Number) response.body().get("revision")).intValue());
        assertEquals(20, weightOf(runtime, "v2"), "v2 放量到 20 应当生效");
        assertEquals(95, weightOf(runtime, "v1"), "未被改动的 v1 权重应原样保留");
        // 入参对象会被 normalizeAndValidate 拷贝，原对象不该被就地改写
        assertEquals(5, route.getTargets().get(1).weight(), "提交不得就地修改调用方传入的路由对象");
    }

    @Test
    void staleRevisionConflictLeavesLiveRouteUntouched() {
        GatewayRuntime runtime = runtime(List.of(grayRoute(95, 5)));
        weight(runtime, 0, "op-1", 20);
        assertEquals(20, weightOf(runtime, "v2"), "前置条件：v2 已放量到 20");

        Response conflict = weight(runtime, 0, "op-2", 50);

        assertEquals(HttpResponseStatus.CONFLICT, conflict.status(), "过期 revision 必须返回 409");
        assertEquals(1, ((Number) conflict.body().get("currentRevision")).intValue(),
                "冲突响应要回带当前版本，调用方据此刷新重试");
        assertEquals(20, weightOf(runtime, "v2"),
                "请求被拒时线上分流必须保持原样——就地改活对象会让流量在没有任何版本记录的情况下被切走");
        assertEquals(1, runtime.getRevision(), "冲突不得推进版本号");
    }

    @Test
    void zeroWeightIsAcceptedAsStopTrafficPrimitive() {
        GatewayRuntime runtime = runtime(List.of(grayRoute(95, 5)));

        Response response = weight(runtime, 0, "op-1", 0);

        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals(0, weightOf(runtime, "v2"),
                "权重置 0 是「停推」原语：分不到流量，但不是没有可接流实例");
    }

    /** 一条 v1/v2 灰度路由，targets 顺序即区间顺序。 */
    private static RouteConfig grayRoute(int v1Weight, int v2Weight) {
        RouteConfig route = new RouteConfig();
        route.setId("demo-api");
        route.setBusinessPrefix("/api/demo");
        route.setTargets(new ArrayList<>(List.of(
                new RouteTarget("demo", "v1", v1Weight), new RouteTarget("demo", "v2", v2Weight))));
        return route;
    }

    private GatewayRuntime runtime(List<RouteConfig> routes) {
        DiscoverySettings settings = new DiscoverySettings();
        settings.setType(DiscoveryType.NAMESERVER);
        GatewayRuntimeConfigManager configManager = new GatewayRuntimeConfigManager(
                new GatewayRuntimeConfigApplier(),
                new RuntimeConfigOverlayStore(tempDir.resolve("gw-overlay-" + System.nanoTime() + ".json")));
        return new GatewayRuntime(0, routes, 1000, 3000, new FilterSettings(), settings, null,
                configManager, "", new RouteOverlayStore(tempDir.resolve("gw-routes-" + System.nanoTime() + ".json")));
    }

    /** 读取当前生效路由里某个 group 的权重。 */
    private static int weightOf(GatewayRuntime runtime, String group) {
        RouteConfig route = runtime.getRouteMatcher().listRoutes().get(0);
        for (RouteTarget target : route.getTargets()) {
            if (group.equals(target.group())) {
                return target.weight();
            }
        }
        throw new AssertionError("生效路由里没有 group=" + group + " 的目标：" + route.getTargets());
    }

    /** 调一次放量/停推接口。 */
    private Response weight(GatewayRuntime runtime, int revision, String operationId, int weight) {
        String payload = "{\"routeId\":\"demo-api\",\"serviceName\":\"demo\",\"group\":\"v2\",\"weight\":" + weight
                + ",\"revision\":" + revision + ",\"operationId\":\"" + operationId + "\"}";
        return post(ManageApiPaths.ROUTES_TARGET_WEIGHT, payload, runtime);
    }

    /**
     * 发一次 POST 并读出状态码与响应体。
     *
     * <p>响应是引用计数对象，这里读完就释放，避免测试进程里堆积未释放缓冲区。
     */
    private Response post(String path, String payload, GatewayRuntime runtime) {
        ByteBuf content = Unpooled.copiedBuffer(payload, StandardCharsets.UTF_8);
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, path, content);
        request.headers().set("Content-Length", content.readableBytes());
        ChannelHandlerContext[] holder = new ChannelHandlerContext[1];
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter() {
            @Override
            public void handlerAdded(ChannelHandlerContext ctx) {
                holder[0] = ctx;
            }
        });
        try {
            new GatewayManageApi(runtime).handle(holder[0], request, path);
            FullHttpResponse response = channel.readOutbound();
            assertNotNull(response, "管理口必须写出一条响应");
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = JsonCodec.fromJson(
                        response.content().toString(StandardCharsets.UTF_8), Map.class);
                return new Response(response.status(), body);
            } finally {
                response.release();
            }
        } finally {
            request.release();
            channel.finishAndReleaseAll();
        }
    }

    /** 一次管理口响应的状态码与已解析响应体。 */
    private record Response(HttpResponseStatus status, Map<String, Object> body) { }
}
