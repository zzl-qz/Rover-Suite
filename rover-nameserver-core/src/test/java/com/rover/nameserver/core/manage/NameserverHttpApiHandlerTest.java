package com.rover.nameserver.core.manage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.common.config.RuntimeConfigOverlayStore;
import com.rover.common.constants.ManageApiPaths;
import com.rover.common.json.JsonCodec;
import com.rover.common.protocol.AckMode;
import com.rover.nameserver.core.clientapi.NameserverClientApi;
import com.rover.nameserver.core.cluster.NameserverGeneration;
import com.rover.nameserver.core.config.NameserverRuntimeConfigApplier;
import com.rover.nameserver.core.config.NameserverRuntimeConfigManager;
import com.rover.nameserver.core.health.HealthChecker;
import com.rover.nameserver.core.metrics.NameserverMetricsRegistry;
import com.rover.nameserver.core.push.PushService;
import com.rover.nameserver.core.push.SubscriptionManager;
import com.rover.nameserver.core.registration.RegistrationService;
import com.rover.nameserver.core.registry.InMemoryServiceRegistry;
import com.rover.nameserver.core.runtime.NameserverRuntime;
import com.rover.nameserver.core.server.NameserverServerOptions;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NameserverHttpApiHandlerTest {

    private static final String CLIENT_TOKEN = "client-secret";
    private static final String ADMIN_TOKEN = "admin-secret";
    private static final String SESSION_ID = "11111111-1111-1111-1111-111111111111";

    @TempDir
    Path tempDir;

    @Test
    void clientAndAdminAuthenticationDomainsNeverAuthorizeEachOther() {
        try (Fixture fixture = fixture()) {
            RoutedResult clientWithAdminToken = fixture.exchange(
                    HttpMethod.POST,
                    NameserverClientApi.REGISTER_PATH,
                    registerJson(),
                    headers -> headers.set("X-Rover-Admin-Token", ADMIN_TOKEN));
            assertEquals(HttpResponseStatus.UNAUTHORIZED, clientWithAdminToken.status());
            assertTrue(clientWithAdminToken.body().contains("UNAUTHORIZED"));

            RoutedResult clientWithClientToken = fixture.exchange(
                    HttpMethod.POST,
                    NameserverClientApi.REGISTER_PATH,
                    registerJson(),
                    headers -> headers.set(HttpHeaderNames.AUTHORIZATION, "Bearer " + CLIENT_TOKEN));
            assertEquals(HttpResponseStatus.OK, clientWithClientToken.status());
            assertTrue(clientWithClientToken.body().contains("\"code\":\"OK\""));

            RoutedResult manageWithClientToken = fixture.exchange(
                    HttpMethod.GET,
                    "/_manage/status",
                    "",
                    headers -> headers.set(HttpHeaderNames.AUTHORIZATION, "Bearer " + CLIENT_TOKEN));
            assertEquals(HttpResponseStatus.UNAUTHORIZED, manageWithClientToken.status());
            assertTrue(manageWithClientToken.body().contains("管理口鉴权失败"));

            RoutedResult manageWithAdminToken = fixture.exchange(
                    HttpMethod.GET,
                    "/_manage/status",
                    "",
                    headers -> headers.set("X-Rover-Admin-Token", ADMIN_TOKEN));
            assertEquals(HttpResponseStatus.OK, manageWithAdminToken.status());
            assertTrue(manageWithAdminToken.body().contains("\"component\":\"nameserver\""));

            RoutedResult health = fixture.exchange(
                    HttpMethod.GET,
                    "/_manage/health",
                    "",
                    headers -> headers.set("X-Rover-Admin-Token", ADMIN_TOKEN));
            assertEquals(HttpResponseStatus.OK, health.status());
            assertTrue(health.body().contains("\"status\":\"UP\""));
        }
    }

    @Test
    void unknownRootPathDoesNotFallThroughToEitherAuthenticationDomain() {
        try (Fixture fixture = fixture()) {
            RoutedResult result = fixture.exchange(
                    HttpMethod.POST,
                    "/not-client-or-manage",
                    "{}",
                    headers -> {
                        headers.set(HttpHeaderNames.AUTHORIZATION, "Bearer wrong");
                        headers.set("X-Rover-Admin-Token", "wrong");
                    });

            assertEquals(HttpResponseStatus.NOT_FOUND, result.status());
            assertTrue(result.body().contains("NOT_FOUND"));
            assertTrue(result.body().contains("unknown HTTP path"));
        }
    }

    @Test
    void configsEndpointReturnsRegisteredConfigItems() {
        try (Fixture fixture = fixture()) {
            RoutedResult result = fixture.exchange(
                    HttpMethod.GET,
                    "/_manage/configs",
                    "",
                    headers -> headers.set("X-Rover-Admin-Token", ADMIN_TOKEN));

            assertEquals(HttpResponseStatus.OK, result.status());
            assertTrue(result.body().contains("nameserver.push.enabled"));
            assertTrue(result.body().contains("nameserver.health.checkIntervalMillis"));
        }
    }

    @Test
    void manageApiErrorStillWritesResponseInsteadOfHanging() {
        // 编译错误桩等场景会抛 Error，基类漏兜会让客户端一个字节都收不到（表现为永久挂起）
        try (Fixture fixture = fixture(runtime -> new NameserverManageApi(runtime) {
            @Override
            protected boolean dispatch(ChannelHandlerContext ctx, FullHttpRequest request, String path) {
                throw new Error("Unresolved compilation problem: boom");
            }
        })) {
            RoutedResult result = fixture.exchange(
                    HttpMethod.GET,
                    "/_manage/configs",
                    "",
                    headers -> headers.set("X-Rover-Admin-Token", ADMIN_TOKEN));

            assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, result.status());
            assertTrue(result.body().contains("manage api error"));
        }
    }

    @Test
    void instancesSnapshotCarriesRevisionEpochAndStableInstanceIds() {
        try (Fixture fixture = fixture()) {
            assertEquals(HttpResponseStatus.OK, fixture.exchange(
                    HttpMethod.POST,
                    NameserverClientApi.REGISTER_PATH,
                    registerJson("demo", "instance-one", SESSION_ID),
                    headers -> headers.set(HttpHeaderNames.AUTHORIZATION, "Bearer " + CLIENT_TOKEN)).status());
            assertEquals(HttpResponseStatus.OK, fixture.exchange(
                    HttpMethod.POST,
                    NameserverClientApi.REGISTER_PATH,
                    registerJson("demo", "instance-two", "22222222-2222-2222-2222-222222222222"),
                    headers -> headers.set(HttpHeaderNames.AUTHORIZATION, "Bearer " + CLIENT_TOKEN)).status());

            RoutedResult result = fixture.exchange(
                    HttpMethod.GET,
                    ManageApiPaths.INSTANCES_SNAPSHOT,
                    "",
                    headers -> headers.set("X-Rover-Admin-Token", ADMIN_TOKEN));
            assertEquals(HttpResponseStatus.OK, result.status());

            @SuppressWarnings("unchecked")
            Map<String, Object> root = JsonCodec.fromJson(result.body(), Map.class);
            // 两次注册各自 bump 一次，聚合 revision 至少为 2；epoch 由注册中心世代提供，不能为空
            assertTrue(((Number) root.get("revision")).longValue() >= 2L);
            assertTrue(String.valueOf(root.get("epoch")).length() > 0);
            assertEquals(2, ((Number) root.get("count")).intValue());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> services = (List<Map<String, Object>>) root.get("services");
            assertEquals(1, services.size());
            Map<String, Object> service = services.get(0);
            assertEquals("demo", service.get("serviceName"));
            assertTrue(((Number) service.get("revision")).longValue() >= 2L);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> instances = (List<Map<String, Object>>) service.get("instances");
            assertEquals(2, instances.size());
            for (Map<String, Object> instance : instances) {
                assertTrue(String.valueOf(instance.get("instanceId")).startsWith("instance-"));
                assertEquals(Boolean.TRUE, instance.get("healthy"));
                assertTrue(((Number) instance.get("lastHeartbeatMillis")).longValue() > 0L);
            }
        }
    }

    private Fixture fixture() {
        return fixture(NameserverManageApi::new);
    }

    private Fixture fixture(Function<NameserverRuntime, NameserverManageApi> manageApiFactory) {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        NameserverMetricsRegistry metrics = new NameserverMetricsRegistry();
        NameserverGeneration generation = NameserverGeneration.processLocal();
        PushService pushService = new PushService(
                new SubscriptionManager(), true, generation, metrics);
        RegistrationService registrationService = new RegistrationService(registry, pushService, metrics);
        NameserverServerOptions options = NameserverServerOptions.builder()
                .port(8888)
                .managePort(8889)
                .token(CLIENT_TOKEN)
                .adminToken(ADMIN_TOKEN)
                .clientApiEnabled(true)
                .writeAckMode(AckMode.SINGLE)
                .replicationFactor(1)
                .pushEnabled(true)
                .build();
        HealthChecker healthChecker = new HealthChecker(
                registry, pushService, 15_000L, 5_000L, 30_000L, metrics);
        NameserverRuntimeConfigManager configManager = new NameserverRuntimeConfigManager(
                new NameserverRuntimeConfigApplier(),
                new RuntimeConfigOverlayStore(tempDir.resolve("runtime-overlay.json")));
        NameserverRuntime runtime = new NameserverRuntime(
                options,
                registry,
                pushService,
                healthChecker,
                configManager,
                metrics,
                registrationService);
        configManager.getApplier().bind(runtime);

        NameserverClientApi clientApi = new NameserverClientApi(
                registrationService, options, generation::epoch, healthChecker::getInstanceExpireMillis);
        NameserverManageApi manageApi = manageApiFactory.apply(runtime);
        EmbeddedChannel channel = new EmbeddedChannel(new NameserverHttpApiHandler(clientApi, manageApi));
        return new Fixture(channel, healthChecker);
    }

    private static String registerJson() {
        return registerJson("svc", "instance-one", SESSION_ID);
    }

    private static String registerJson(String serviceName, String instanceId, String sessionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serviceName", serviceName);
        body.put("instanceId", instanceId);
        body.put("sessionId", sessionId);
        body.put("host", "127.0.0.1");
        body.put("port", 8080);
        return JsonCodec.toJson(body);
    }

    private record RoutedResult(HttpResponseStatus status, String body) {
    }

    private record Fixture(EmbeddedChannel channel, HealthChecker healthChecker) implements AutoCloseable {

        RoutedResult exchange(
                HttpMethod method,
                String path,
                String body,
                Consumer<io.netty.handler.codec.http.HttpHeaders> headerCustomizer) {
            FullHttpRequest request = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    method,
                    path,
                    Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
            request.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            headerCustomizer.accept(request.headers());

            channel.writeInbound(request);
            FullHttpResponse response = channel.readOutbound();
            assertNotNull(response, "HTTP handler 必须写出一条响应");
            try {
                return new RoutedResult(
                        response.status(), response.content().toString(StandardCharsets.UTF_8));
            } finally {
                response.release();
            }
        }

        @Override
        public void close() {
            channel.finishAndReleaseAll();
            healthChecker.shutdown();
        }
    }
}
