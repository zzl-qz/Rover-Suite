package com.rover.gateway.core.manage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 整表提交（PUT /_manage/routes、POST /_manage/routes/preview）的入参契约测试。
 *
 * 守住的是最贵的一类事故：请求体写错（漏字段、拼错键名）被当成「空路由表」接受，
 * 逐条校验全部通过、落盘成功、线上全站 404，而且重启也恢复不了。
 * 因此这里逐项断言「错了就 400」，并检查被拒的请求没有留下任何落盘痕迹。
 */
class GatewayManageRoutesTableTest {

    /** 落盘的覆盖文件名，用来断言被拒的请求确实没写盘。 */
    private static final String OVERLAY_FILE = "routes-overlay.json";

    @TempDir
    Path tempDir;

    @Test
    void putWithoutRoutesFieldIsRejectedAndNothingIsWritten() {
        GatewayRuntime runtime = runtime();

        Response response = send(HttpMethod.PUT, ManageApiPaths.ROUTES,
                "{\"revision\":0,\"operationId\":\"op-1\"}", runtime);

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        assertTrue(String.valueOf(response.body().get("message")).contains("routes"),
                "报错要点名缺的是哪个字段，实际=" + response.body().get("message"));
        assertEquals(1, runtime.getRouteMatcher().listRoutes().size(), "被拒的请求不得改动生效路由");
        assertEquals(0, runtime.getRevision(), "被拒的请求不得推进版本号");
        assertFalse(Files.exists(overlayPath()), "被拒的请求绝不能落盘，否则重启后就真没了");
    }

    @Test
    void putWithMisspelledRoutesKeyIsRejected() {
        GatewayRuntime runtime = runtime();

        // 键名写成 route（少个 s）是最常见的笔误，不能当成空表
        Response response = send(HttpMethod.PUT, ManageApiPaths.ROUTES,
                "{\"revision\":0,\"route\":[{\"businessPrefix\":\"/api/demo\"}]}", runtime);

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        assertEquals(1, runtime.getRouteMatcher().listRoutes().size());
        assertFalse(Files.exists(overlayPath()), "被拒的请求绝不能落盘");
    }

    @Test
    void putWithNonArrayRoutesIsRejected() {
        GatewayRuntime runtime = runtime();

        Response response = send(HttpMethod.PUT, ManageApiPaths.ROUTES,
                "{\"revision\":0,\"routes\":{\"businessPrefix\":\"/api/demo\"}}", runtime);

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        assertTrue(String.valueOf(response.body().get("message")).contains("数组"),
                "报错要说明 routes 必须是数组，实际=" + response.body().get("message"));
        assertEquals(1, runtime.getRouteMatcher().listRoutes().size());
        assertFalse(Files.exists(overlayPath()));
    }

    @Test
    void putWithNonObjectElementIsRejected() {
        GatewayRuntime runtime = runtime();

        // 元素是字符串：宽松解析会把它静默丢掉，于是「提交一条路由」变成「清空路由表」
        Response response = send(HttpMethod.PUT, ManageApiPaths.ROUTES,
                "{\"revision\":0,\"routes\":[\"/api/demo\"]}", runtime);

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        assertEquals(1, runtime.getRouteMatcher().listRoutes().size());
        assertFalse(Files.exists(overlayPath()));
    }

    @Test
    void explicitEmptyArrayClearsRouteTable() {
        GatewayRuntime runtime = runtime();

        // 显式写 [] 才是「确实要清空」，这条路径必须正常生效
        Response response = send(HttpMethod.PUT, ManageApiPaths.ROUTES,
                "{\"revision\":0,\"operationId\":\"op-clear\",\"routes\":[]}", runtime);

        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals("APPLIED", response.body().get("status"));
        assertEquals(1, ((Number) response.body().get("revision")).intValue());
        assertEquals(0, ((Number) response.body().get("routeCount")).intValue());
        assertTrue(runtime.getRouteMatcher().listRoutes().isEmpty(), "显式清空应当真的清空生效路由");
    }

    @Test
    void previewWithoutRoutesFieldIsRejected() {
        GatewayRuntime runtime = runtime();

        Response response = send(HttpMethod.POST, ManageApiPaths.ROUTES_PREVIEW,
                "{\"revision\":0}", runtime);

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        assertTrue(String.valueOf(response.body().get("message")).contains("routes"),
                "预览同样要点名缺的字段，实际=" + response.body().get("message"));
    }

    /** 一条合法路由的运行时，作为「被拒请求不得改动它」的对照。 */
    private GatewayRuntime runtime() {
        RouteConfig route = new RouteConfig();
        route.setId("demo-api");
        route.setBusinessPrefix("/api/demo");
        route.setTargetUrl("http://127.0.0.1:9100");
        DiscoverySettings settings = new DiscoverySettings();
        settings.setType(DiscoveryType.NAMESERVER);
        GatewayRuntimeConfigManager configManager = new GatewayRuntimeConfigManager(
                new GatewayRuntimeConfigApplier(),
                new RuntimeConfigOverlayStore(tempDir.resolve("gw-overlay.json")));
        return new GatewayRuntime(0, List.of(route), 1000, 3000, new FilterSettings(), settings, null,
                configManager, "", new RouteOverlayStore(overlayPath()));
    }

    private Path overlayPath() {
        return tempDir.resolve(OVERLAY_FILE);
    }

    /** 发一次请求并读出状态码与响应体。 */
    private Response send(HttpMethod method, String path, String payload, GatewayRuntime runtime) {
        ByteBuf content = Unpooled.copiedBuffer(payload, StandardCharsets.UTF_8);
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, path, content);
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
