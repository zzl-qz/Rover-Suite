package com.rover.gateway.core.manage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.common.config.RuntimeConfigOverlayStore;
import com.rover.common.constants.ManageApiPaths;
import com.rover.common.json.JsonCodec;
import com.rover.gateway.core.config.GatewayRuntimeConfigApplier;
import com.rover.gateway.core.config.GatewayRuntimeConfigManager;
import com.rover.gateway.core.discovery.DiscoverySettings;
import com.rover.gateway.core.filter.FilterSettings;
import com.rover.gateway.core.route.RouteOverlayStore;
import com.rover.gateway.core.runtime.GatewayRuntime;
import com.rover.gateway.core.trace.RequestTrace;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** /_manage/traces 过滤契约测试：slow 只取慢请求，error 只取 5xx（快失败也能被采到）。 */
class GatewayManageTracesTest {

    @TempDir
    Path tempDir;

    private GatewayRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = runtime();
        runtime.getTraceBuffer().append(trace("t200", 200, 10, false));
        runtime.getTraceBuffer().append(trace("t404", 404, 20, false));
        runtime.getTraceBuffer().append(trace("t500", 500, 30, false));   // 快失败
        runtime.getTraceBuffer().append(trace("t502s", 502, 500, true));  // 又慢又 5xx
    }

    @Test
    void errorFilterReturnsOnly5xx() {
        List<Map<String, Object>> rows = traces("error=true");
        assertEquals(2, rows.size(), "error 过滤应只返回 statusCode>=500 的链路");
        List<String> ids = ids(rows);
        assertTrue(ids.contains("t500"));
        assertTrue(ids.contains("t502s"));
    }

    @Test
    void slowFilterReturnsOnlySlow() {
        List<Map<String, Object>> rows = traces("slow=true");
        assertEquals(1, rows.size(), "slow 过滤应只返回慢请求");
        assertEquals("t502s", rows.get(0).get("traceId"));
    }

    @Test
    void noFilterReturnsAll() {
        assertEquals(4, traces(null).size());
        assertEquals(4, traces("error=false").size(), "error=false 视为不过滤");
    }

    private static RequestTrace trace(String id, int status, long cost, boolean slow) {
        return new RequestTrace(id, "GET", "/p", "r1", "", status, System.currentTimeMillis(), cost, slow, List.of());
    }

    private static List<String> ids(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> String.valueOf(r.get("traceId"))).toList();
    }

    private GatewayRuntime runtime() {
        GatewayRuntimeConfigManager configManager = new GatewayRuntimeConfigManager(
                new GatewayRuntimeConfigApplier(),
                new RuntimeConfigOverlayStore(tempDir.resolve("gw-overlay-" + System.nanoTime() + ".json")));
        return new GatewayRuntime(0, List.of(), 1000, 3000, new FilterSettings(), DiscoverySettings.staticDefaults(),
                null, configManager, "", new RouteOverlayStore(tempDir.resolve("gw-routes-" + System.nanoTime() + ".json")));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> traces(String query) {
        ChannelHandlerContext[] holder = new ChannelHandlerContext[1];
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter() {
            @Override
            public void handlerAdded(ChannelHandlerContext ctx) {
                holder[0] = ctx;
            }
        });
        String uri = ManageApiPaths.TRACES + (query == null || query.isBlank() ? "" : "?" + query);
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, uri, Unpooled.EMPTY_BUFFER);
        try {
            new GatewayManageApi(runtime).handle(holder[0], request, ManageApiPaths.TRACES);
            FullHttpResponse response = channel.readOutbound();
            try {
                Map<String, Object> root = JsonCodec.fromJson(
                        response.content().toString(StandardCharsets.UTF_8), Map.class);
                return (List<Map<String, Object>>) root.get("traces");
            } finally {
                response.release();
            }
        } finally {
            request.release();
            channel.finishAndReleaseAll();
        }
    }
}
