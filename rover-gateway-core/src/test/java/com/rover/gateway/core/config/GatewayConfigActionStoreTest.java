package com.rover.gateway.core.config;

import static org.junit.jupiter.api.Assertions.*;

import com.rover.common.config.RuntimeConfigOverlayStore;
import com.rover.common.constants.ManageApiPaths;
import com.rover.gateway.core.discovery.DiscoverySettings;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.gateway.core.filter.FilterSettings;
import com.rover.gateway.core.route.RouteOverlayStore;
import com.rover.gateway.core.runtime.GatewayRuntime;
import com.rover.gateway.core.manage.GatewayManageApi;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.List;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayConfigActionStoreTest {
    @TempDir Path directory;

    @Test
    void compareAndSetIsAllowlistedIdempotentAndAuditableAcrossRestart() {
        Path routePath = directory.resolve("routes.json");
        var config = configs(directory.resolve("config.json"));
        var store = new GatewayConfigActionStore(routePath, config);
        var applied = store.execute("cfg-1", GatewayConfigActionStore.TRACE_SAMPLE_RATE, "1", "0", "operator");
        assertEquals("APPLIED", applied.status());
        assertEquals("0", value(config));
        assertTrue(store.execute("cfg-1", GatewayConfigActionStore.TRACE_SAMPLE_RATE, "1", "0", "operator").replayed());
        assertThrows(IllegalStateException.class, () -> store.execute("cfg-1", GatewayConfigActionStore.TRACE_SAMPLE_RATE, "1", "0", "other"));
        assertThrows(IllegalArgumentException.class, () -> store.execute("cfg-2", "gateway.filter.enabled", "true", "false", "operator"));
        assertThrows(IllegalArgumentException.class, () -> store.execute("cfg-3", GatewayConfigActionStore.TRACE_SAMPLE_RATE, "0.5", "0", "operator"));

        var restartedConfig = configs(directory.resolve("config.json"));
        var restarted = new GatewayConfigActionStore(routePath, restartedConfig);
        assertEquals("APPLIED", restarted.query("cfg-1").status());
        assertEquals("0", value(restartedConfig));
        var stale = restarted.execute("cfg-2", GatewayConfigActionStore.TRACE_SAMPLE_RATE, "1", "0", "operator");
        assertEquals("PRECONDITION_FAILED", stale.status());
        assertEquals("0", value(restartedConfig));
    }

    @Test
    void queryNeverReplaysAWriteAfterCrashAndReturnsUnknownUntilObserved() throws Exception {
        Path routePath = directory.resolve("crash-routes.json");
        var config = configs(directory.resolve("crash-config.json"));
        var store = new GatewayConfigActionStore(routePath, config);
        Path db = routePath.resolveSibling(routePath.getFileName() + ".config-actions");
        String url = "jdbc:h2:file:" + db.toAbsolutePath().toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=5000";
        try (var c = DriverManager.getConnection(url, "sa", ""); var p = c.prepareStatement(
                "INSERT INTO gateway_config_action(operation_id,request_hash,config_key,expected_value,desired_value,actor,status,created_at,updated_at) VALUES(?,?,?,?,?,?, 'PREPARED',?,?)")) {
            p.setString(1, "crash-1"); p.setString(2, requestHash(GatewayConfigActionStore.TRACE_SAMPLE_RATE, "1", "0", "operator")); p.setString(3, GatewayConfigActionStore.TRACE_SAMPLE_RATE);
            p.setString(4, "1"); p.setString(5, "0"); p.setString(6, "operator"); p.setLong(7, 1); p.setLong(8, 1); p.executeUpdate();
        }
        var unknown = store.query("crash-1");
        assertEquals("UNKNOWN", unknown.status());
        assertEquals("1", unknown.result().get("observedValue"));
        assertEquals("1", value(config), "lookup must not replay a possibly committed write");
        assertEquals("UNKNOWN", store.execute("crash-1", GatewayConfigActionStore.TRACE_SAMPLE_RATE, "1", "0", "operator").status());
        assertEquals("1", value(config), "replaying the same request must remain read-only");
        assertThrows(IllegalStateException.class, () -> store.execute("crash-1", GatewayConfigActionStore.TRACE_SAMPLE_RATE, "1", "0", "different-operator"));
        assertEquals("1", value(config), "a conflicting retry must also remain read-only");
        assertTrue(config.updateTraceSampleRateIfCurrent("1", "0"));
        var observedDesired = store.query("crash-1");
        assertEquals("UNKNOWN", observedDesired.status(), "observing the desired value still cannot prove this operation wrote it");
        assertEquals("0", observedDesired.result().get("observedValue"));
        assertEquals("0", value(config));
    }

    @Test
    void preparedReceiptStaysUnknownWhenDesiredValueIsAlreadyPresent() throws Exception {
        Path routePath = directory.resolve("ambiguous-routes.json");
        var config = configs(directory.resolve("ambiguous-config.json"));
        var store = new GatewayConfigActionStore(routePath, config);
        Path db = routePath.resolveSibling(routePath.getFileName() + ".config-actions");
        String url = "jdbc:h2:file:" + db.toAbsolutePath().toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=5000";
        try (var c = DriverManager.getConnection(url, "sa", ""); var p = c.prepareStatement(
                "INSERT INTO gateway_config_action(operation_id,request_hash,config_key,expected_value,desired_value,actor,status,created_at,updated_at) VALUES(?,?,?,?,?,?, 'PREPARED',?,?)")) {
            p.setString(1, "ambiguous-1");
            p.setString(2, requestHash(GatewayConfigActionStore.TRACE_SAMPLE_RATE, "1", "0", "operator"));
            p.setString(3, GatewayConfigActionStore.TRACE_SAMPLE_RATE);
            p.setString(4, "1"); p.setString(5, "0"); p.setString(6, "operator"); p.setLong(7, 1); p.setLong(8, 1); p.executeUpdate();
        }
        config.updateConfig(GatewayConfigActionStore.TRACE_SAMPLE_RATE, "0");
        assertEquals("UNKNOWN", store.query("ambiguous-1").status());
        assertEquals("UNKNOWN", store.execute("ambiguous-1", GatewayConfigActionStore.TRACE_SAMPLE_RATE, "1", "0", "operator").status());
        assertEquals("0", value(config));
    }

    @Test
    void updatePersistenceFailureReturnsUnknownWithoutAutomaticSecondWrite() throws Exception {
        Path routePath = directory.resolve("broken-config-routes.json");
        Path overlayPath = directory.resolve("blocked-overlay");
        java.nio.file.Files.createDirectory(overlayPath);
        var config = new GatewayRuntimeConfigManager(new GatewayRuntimeConfigApplier(), new RuntimeConfigOverlayStore(overlayPath));
        config.seed(GatewayRuntimeConfigKeys.TRACE_SAMPLE_RATE, "1");
        var store = new GatewayConfigActionStore(routePath, config);

        var unknown = store.execute("broken-1", GatewayConfigActionStore.TRACE_SAMPLE_RATE, "1", "0", "operator");
        assertEquals("UNKNOWN", unknown.status());
        assertEquals("1", value(config), "failed config persistence must roll the in-memory value back");
        assertEquals("UNKNOWN", store.query("broken-1").status());
        assertEquals("UNKNOWN", store.execute("broken-1", GatewayConfigActionStore.TRACE_SAMPLE_RATE, "1", "0", "operator").status());
        assertEquals("1", value(config), "retrying an unknown action must never write again");
    }

    @Test
    void manageEndpointRequiresAdminTokenAndReturnsDurableReceipt() {
        Path routePath = directory.resolve("api-routes.json");
        var config = configs(directory.resolve("api-config.json"));
        var runtime = runtime(config, routePath);
        var api = new GatewayManageApi(runtime);
        String body = "{\"operationId\":\"api-1\",\"key\":\"" + GatewayConfigActionStore.TRACE_SAMPLE_RATE
                + "\",\"expectedValue\":\"1\",\"desiredValue\":\"0\",\"actor\":\"agent-admin\"}";
        var denied = call(api, HttpMethod.POST, ManageApiPaths.CONFIG_ACTIONS, body, false);
        assertEquals(401, denied.status());
        var applied = call(api, HttpMethod.POST, ManageApiPaths.CONFIG_ACTIONS, body, true);
        assertEquals(200, applied.status(), applied.body());
        assertTrue(applied.body().contains("\"status\":\"APPLIED\""), applied.body());
        var replayed = call(api, HttpMethod.POST, ManageApiPaths.CONFIG_ACTIONS, body, true);
        assertTrue(replayed.body().contains("\"replayed\":true"), replayed.body());
        String conflictBody = body.replace("agent-admin", "different-actor");
        var conflict = call(api, HttpMethod.POST, ManageApiPaths.CONFIG_ACTIONS, conflictBody, true);
        assertEquals(409, conflict.status(), conflict.body());
        assertEquals("0", value(config), "an operation ID conflict must not mutate configuration");
        var queried = call(api, HttpMethod.GET, ManageApiPaths.CONFIG_ACTIONS + "/api-1", "", true);
        assertEquals(200, queried.status());
        assertTrue(queried.body().contains("\"operationId\":\"api-1\""));
    }

    private GatewayRuntime runtime(GatewayRuntimeConfigManager config, Path routePath) {
        var settings = new DiscoverySettings(); settings.setType(DiscoveryType.STATIC);
        return new GatewayRuntime(0, List.of(), 1000, 3000, new FilterSettings(), settings, null, config, "secret", new RouteOverlayStore(routePath));
    }
    private static GatewayRuntimeConfigManager configs(Path overlay) {
        var config = new GatewayRuntimeConfigManager(new GatewayRuntimeConfigApplier(), new RuntimeConfigOverlayStore(overlay));
        config.seed(GatewayRuntimeConfigKeys.TRACE_SAMPLE_RATE, "1");
        config.loadOverlayIfPresent();
        return config;
    }
    private static String value(GatewayRuntimeConfigManager config) {
        return config.listConfigs().stream().filter(item -> GatewayConfigActionStore.TRACE_SAMPLE_RATE.equals(item.getKey()))
                .findFirst().orElseThrow().getValue();
    }
    private static Response call(GatewayManageApi api, HttpMethod method, String path, String body, boolean authorized) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, path, Unpooled.wrappedBuffer(bytes));
        request.headers().set("Content-Length", bytes.length);
        if (authorized) { request.headers().set("X-Rover-Admin-Token", "secret"); }
        ChannelHandlerContext[] context = new ChannelHandlerContext[1];
        var channel = new io.netty.channel.embedded.EmbeddedChannel(new ChannelInboundHandlerAdapter() {
            @Override public void handlerAdded(ChannelHandlerContext ctx) { context[0] = ctx; }
        });
        try {
            api.handle(context[0], request, path);
            FullHttpResponse response = channel.readOutbound();
            try { return new Response(response.status().code(), response.content().toString(StandardCharsets.UTF_8)); }
            finally { response.release(); }
        } finally { request.release(); channel.finishAndReleaseAll(); }
    }
    private record Response(int status, String body) { }
    private static String requestHash(String key, String expected, String desired, String actor) throws Exception {
        byte[] bytes = (key + "|" + expected + "|" + desired + "|" + actor).getBytes(StandardCharsets.UTF_8);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
