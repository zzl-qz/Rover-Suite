package com.rover.nameserver.core.manage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.common.config.RuntimeConfigOverlayStore;
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
import java.util.Map;
import java.util.function.Consumer;
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

    private Fixture fixture() {
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
        NameserverManageApi manageApi = new NameserverManageApi(runtime);
        EmbeddedChannel channel = new EmbeddedChannel(new NameserverHttpApiHandler(clientApi, manageApi));
        return new Fixture(channel, healthChecker);
    }

    private static String registerJson() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serviceName", "svc");
        body.put("instanceId", "instance-one");
        body.put("sessionId", SESSION_ID);
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
