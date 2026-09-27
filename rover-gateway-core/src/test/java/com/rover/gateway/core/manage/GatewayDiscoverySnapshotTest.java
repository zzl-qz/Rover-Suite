package com.rover.gateway.core.manage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.common.config.RuntimeConfigOverlayStore;
import com.rover.common.constants.ManageApiPaths;
import com.rover.common.json.JsonCodec;
import com.rover.common.model.ServiceInstance;
import com.rover.common.spi.discovery.ServiceDiscovery;
import com.rover.gateway.core.config.GatewayRuntimeConfigApplier;
import com.rover.gateway.core.config.GatewayRuntimeConfigManager;
import com.rover.gateway.core.discovery.DiscoverySettings;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.gateway.core.discovery.NameserverServiceDiscovery;
import com.rover.gateway.core.filter.FilterSettings;
import com.rover.gateway.core.route.RouteOverlayStore;
import com.rover.gateway.core.runtime.GatewayRuntime;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** /_manage/discovery/snapshot JSON 契约测试：静态发现如实报不支持，动态发现报网关观察到的版本。 */
class GatewayDiscoverySnapshotTest {

    @TempDir
    Path tempDir;

    @Test
    void staticDiscoveryReportsUnsupportedWithoutThrowing() {
        GatewayRuntime runtime = runtime(DiscoverySettings.staticDefaults(), null);

        Map<String, Object> root = snapshot(runtime);

        assertEquals("STATIC", root.get("discoveryType"));
        assertEquals(Boolean.FALSE, root.get("supported"));
        assertEquals(0, ((Number) root.get("subscribeCount")).intValue());
        assertTrue(((List<?>) root.get("subscriptions")).isEmpty());
    }

    @Test
    void nameserverDiscoveryReportsObservedSubscriptionsWithRevisionAndCounts() {
        DiscoverySettings settings = new DiscoverySettings();
        settings.setType(DiscoveryType.NAMESERVER);
        DiscoverySettings.ServiceSubscribeSpec spec = new DiscoverySettings.ServiceSubscribeSpec();
        spec.setServiceName("demo");
        spec.setGroup("v1");
        settings.setSubscribeServices(List.of(spec));
        NameserverServiceDiscovery discovery = new NameserverServiceDiscovery(settings);
        // 直接向缓存写入一次「查询对账」结果，模拟网关已观察到该服务
        discovery.getInstanceCache().putSnapshotFromQuery(
                "demo", "v1", "epoch-1", 3L, List.of(instance("a", true), instance("b", false)));

        Map<String, Object> root = snapshot(runtime(settings, discovery));

        assertEquals("NAMESERVER", root.get("discoveryType"));
        assertEquals(Boolean.TRUE, root.get("supported"));
        assertEquals(1, ((Number) root.get("subscribeCount")).intValue());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subscriptions = (List<Map<String, Object>>) root.get("subscriptions");
        Map<String, Object> row = subscriptions.get(0);
        assertEquals("demo", row.get("serviceName"));
        assertEquals("v1", row.get("group"));
        assertEquals(3L, ((Number) row.get("revision")).longValue());
        assertEquals("epoch-1", row.get("epoch"));
        assertEquals(2, ((Number) row.get("instanceCount")).intValue());
        assertEquals(1, ((Number) row.get("healthyCount")).intValue());
    }

    private GatewayRuntime runtime(DiscoverySettings settings, ServiceDiscovery discovery) {
        GatewayRuntimeConfigManager configManager = new GatewayRuntimeConfigManager(
                new GatewayRuntimeConfigApplier(),
                new RuntimeConfigOverlayStore(tempDir.resolve("gw-overlay-" + System.nanoTime() + ".json")));
        return new GatewayRuntime(0, List.of(), 1000, 3000, new FilterSettings(), settings, discovery,
                configManager, "", new RouteOverlayStore(tempDir.resolve("gw-routes-" + System.nanoTime() + ".json")));
    }

    private static ServiceInstance instance(String instanceId, boolean healthy) {
        ServiceInstance instance = new ServiceInstance();
        instance.setServiceName("demo");
        instance.setGroup("v1");
        instance.setInstanceId(instanceId);
        instance.setHost("127.0.0.1");
        instance.setPort(9100);
        instance.setHealthy(healthy);
        return instance;
    }

    private Map<String, Object> snapshot(GatewayRuntime runtime) {
        ChannelHandlerContext[] holder = new ChannelHandlerContext[1];
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter() {
            @Override
            public void handlerAdded(ChannelHandlerContext ctx) {
                holder[0] = ctx;
            }
        });
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, ManageApiPaths.DISCOVERY_SNAPSHOT, Unpooled.EMPTY_BUFFER);
        try {
            new GatewayManageApi(runtime).handle(holder[0], request, ManageApiPaths.DISCOVERY_SNAPSHOT);
            FullHttpResponse response = channel.readOutbound();
            assertNotNull(response, "管理口必须写出一条响应");
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> root = JsonCodec.fromJson(
                        response.content().toString(StandardCharsets.UTF_8), Map.class);
                return root;
            } finally {
                response.release();
            }
        } finally {
            request.release();
            channel.finishAndReleaseAll();
        }
    }
}
