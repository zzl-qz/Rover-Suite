package com.rover.gateway.core.manage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.common.config.RuntimeConfigOverlayStore;
import com.rover.common.constants.ManageApiPaths;
import com.rover.common.json.JsonCodec;
import com.rover.common.model.ServiceInstance;
import com.rover.gateway.core.config.GatewayRuntimeConfigApplier;
import com.rover.gateway.core.config.GatewayRuntimeConfigManager;
import com.rover.gateway.core.discovery.DiscoverySettings;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.gateway.core.discovery.NameserverServiceDiscovery;
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

class GatewayManageIsolationTest {
    @TempDir Path tempDir;

    @Test
    void requiresExactRouteInstanceIdentityAndLiveCapacityForIsolationAndRestore() {
        ServiceInstance one = instance("orders-1", "10.0.0.1");
        ServiceInstance two = instance("orders-2", "10.0.0.2");
        NameserverServiceDiscovery discovery = discovery(List.of(one, two), 1);
        RouteConfig route = route();
        GatewayRuntime runtime = runtime(discovery, route);
        String valid = request(route, one, "ISOLATE", "iso-1", 0, 1);

        Response badUid = post(runtime, valid.replace("uid-a", "stale-uid"));
        assertEquals(HttpResponseStatus.BAD_REQUEST, badUid.status);
        Response invalidMinimum = post(runtime, valid.replace("\"minimumHealthyAfter\":1", "\"minimumHealthyAfter\":2"));
        assertEquals(HttpResponseStatus.BAD_REQUEST, invalidMinimum.status);
        Response isolated = post(runtime, valid);
        assertEquals(HttpResponseStatus.OK, isolated.status, isolated.body);
        assertEquals(1, runtime.getInstanceIsolationStore().active(route.getResourceUid(), route.getGeneration(), route.getId(), "orders", "blue").size());

        discovery.getInstanceCache().putSnapshotFromQuery("orders", "blue", "epoch", 2, List.of());
        String restore = request(route, one, "RESTORE", "restore-1", 1, 1);
        Response rejectedRestore = post(runtime, restore);
        assertEquals(HttpResponseStatus.BAD_REQUEST, rejectedRestore.status, "恢复时只接受当前健康实例快照");
        assertTrue(runtime.getInstanceIsolationStore().active(route.getResourceUid(), route.getGeneration(), route.getId(), "orders", "blue").get(0).active());

        discovery.getInstanceCache().putSnapshotFromQuery("orders", "blue", "epoch", 3, List.of(one, two));
        Response restored = post(runtime, restore);
        assertEquals(HttpResponseStatus.OK, restored.status);
        assertTrue(runtime.getInstanceIsolationStore().active(route.getResourceUid(), route.getGeneration(), route.getId(), "orders", "blue").isEmpty());
        assertTrue(get(runtime).body.contains("RESTORED"));
    }

    @Test
    void acceptsAnyDiscoveredGroupForAnUnrestrictedRouteAndUsesManualSource() {
        ServiceInstance blue = instance("orders-blue", "10.0.0.3"); blue.setGroup("blue");
        ServiceInstance other = instance("orders-green", "10.0.0.2"); other.setGroup("green");
        NameserverServiceDiscovery discovery = discovery(List.of(blue, other), 1);
        discovery.getInstanceCache().putSnapshotFromQuery("orders", "", "epoch", 1, List.of(blue, other));
        // Empty route group covers registered groups. The persisted identity retains the instance's real group.
        RouteConfig route = route(); route.setTargets(new ArrayList<>(List.of(new RouteTarget("orders", "", 100))));
        GatewayRuntime runtime = runtime(discovery, route);
        String valid = request(route, blue, "ISOLATE", "iso-blue", 0, 1);
        Response isolated = post(runtime, valid);
        assertEquals(HttpResponseStatus.OK, isolated.status, isolated.body);
        assertEquals("MANUAL", runtime.getInstanceIsolationStore().list().get(0).source().name());
    }

    @Test
    void activeIsolationBlocksRouteGenerationChangeUntilInstanceIsRestored() {
        ServiceInstance one = instance("orders-1", "10.0.0.1");
        ServiceInstance two = instance("orders-2", "10.0.0.2");
        NameserverServiceDiscovery discovery = discovery(List.of(one, two), 1);
        RouteConfig route = route();
        GatewayRuntime runtime = runtime(discovery, route);

        assertEquals(HttpResponseStatus.OK, post(runtime, request(route, one, "ISOLATE", "iso-before-edit", 0, 1)).status);
        RouteConfig edited = route();
        edited.setBusinessPrefix("/orders-v2");
        var blocked = org.junit.jupiter.api.Assertions.assertThrows(
                com.rover.common.manage.ManageApiException.class,
                () -> runtime.applyRoutes(0, "edit-during-isolation", List.of(edited)));
        assertEquals("ACTIVE_INSTANCE_ISOLATION", blocked.getDetails().get("code"));
        assertEquals(0, runtime.getRevision());
        assertEquals(2, runtime.getRouteMatcher().listRoutes().get(0).getGeneration());

        String restore = request(route, one, "RESTORE", "restore-before-edit", 1, 1);
        assertEquals(HttpResponseStatus.OK, post(runtime, restore).status);
        var applied = runtime.applyRoutes(0, "edit-after-restore", List.of(edited));
        assertEquals("APPLIED", applied.status());
        assertEquals(3, runtime.getRouteMatcher().listRoutes().get(0).getGeneration());
    }

    private GatewayRuntime runtime(NameserverServiceDiscovery discovery, RouteConfig route) {
        DiscoverySettings settings = new DiscoverySettings();
        settings.setType(DiscoveryType.NAMESERVER);
        var config = new GatewayRuntimeConfigManager(new GatewayRuntimeConfigApplier(),
                new RuntimeConfigOverlayStore(tempDir.resolve("config.json")));
        return new GatewayRuntime(0, List.of(route), 1000, 3000, new FilterSettings(), settings, discovery, config,
                "", new RouteOverlayStore(tempDir.resolve("routes.json")));
    }

    private static NameserverServiceDiscovery discovery(List<ServiceInstance> instances, long revision) {
        DiscoverySettings settings = new DiscoverySettings();
        NameserverServiceDiscovery discovery = new NameserverServiceDiscovery(settings);
        discovery.getInstanceCache().putSnapshotFromQuery("orders", "blue", "epoch", revision, instances);
        return discovery;
    }

    private static RouteConfig route() {
        RouteConfig route = new RouteConfig();
        route.setId("orders-api"); route.setResourceUid("uid-a"); route.setGeneration(2);
        route.setBusinessPrefix("/orders"); route.setTargets(new ArrayList<>(List.of(new RouteTarget("orders", "blue", 100))));
        return route;
    }

    private static ServiceInstance instance(String id, String host) {
        ServiceInstance result = new ServiceInstance(); result.setServiceName("orders"); result.setGroup("blue");
        result.setInstanceId(id); result.setHost(host); result.setPort(8080); result.setHealthy(true); return result;
    }

    private static String request(RouteConfig route, ServiceInstance instance, String operation, String operationId, int version, int minimum) {
        return "{\"operation\":\"" + operation + "\",\"operationId\":\"" + operationId + "\","
                + "\"source\":\"MANUAL\",\"requestedBy\":\"oncall\",\"routeUid\":\"" + route.getResourceUid() + "\","
                + "\"generation\":2,\"routeId\":\"orders-api\",\"serviceName\":\"orders\",\"group\":\"blue\","
                + "\"instanceId\":\"" + instance.getInstanceId() + "\",\"host\":\"" + instance.getHost() + "\",\"port\":8080,"
                + "\"expectedVersion\":" + version + ",\"minimumHealthyAfter\":" + minimum + ","
                + "\"capacityEvidence\":\"operator checked cache\",\"reason\":\"manual test\","
                + "\"expiresAtMillis\":" + (System.currentTimeMillis() + 60_000) + "}";
    }

    private static Response post(GatewayRuntime runtime, String payload) { return call(runtime, HttpMethod.POST, payload); }
    private static Response get(GatewayRuntime runtime) { return call(runtime, HttpMethod.GET, ""); }

    private static Response call(GatewayRuntime runtime, HttpMethod method, String payload) {
        String path = ManageApiPaths.ROUTE_INSTANCE_ISOLATIONS;
        ByteBuf content = Unpooled.copiedBuffer(payload, StandardCharsets.UTF_8);
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, path, content);
        request.headers().set("Content-Length", content.readableBytes());
        ChannelHandlerContext[] context = new ChannelHandlerContext[1];
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter() {
            @Override public void handlerAdded(ChannelHandlerContext ctx) { context[0] = ctx; }
        });
        try {
            new GatewayManageApi(runtime).handle(context[0], request, path);
            FullHttpResponse response = channel.readOutbound(); assertNotNull(response);
            try { return new Response(response.status(), response.content().toString(StandardCharsets.UTF_8)); }
            finally { response.release(); }
        } finally { request.release(); channel.finishAndReleaseAll(); }
    }

    private record Response(HttpResponseStatus status, String body) {}

}
