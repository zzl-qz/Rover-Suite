package com.rover.nameserver.core.clientapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.common.json.JsonCodec;
import com.rover.nameserver.core.cluster.NameserverGeneration;
import com.rover.nameserver.core.metrics.NameserverMetricsRegistry;
import com.rover.nameserver.core.push.PushService;
import com.rover.nameserver.core.push.SubscriptionManager;
import com.rover.nameserver.core.registration.RegistrationService;
import com.rover.nameserver.core.registry.InMemoryServiceRegistry;
import com.rover.nameserver.core.server.NameserverServerOptions;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NameserverClientApiTest {

    private static final String CLIENT_TOKEN = "client-secret";
    private static final String SESSION_ONE = "11111111-1111-1111-1111-111111111111";
    private static final String SESSION_TWO = "22222222-2222-2222-2222-222222222222";

    @Test
    void clientApiIsOptInByDefault() {
        assertFalse(NameserverServerOptions.builder().build().isClientApiEnabled());
    }

    @Test
    void registerRetryHeartbeatAndUnregisterFollowLeaseSemantics() {
        Fixture fixture = fixture(true, CLIENT_TOKEN);

        ApiResult first = fixture.call(
                HttpMethod.POST,
                NameserverClientApi.REGISTER_PATH,
                registerJson(SESSION_ONE),
                "Bearer " + CLIENT_TOKEN);
        assertEquals(HttpResponseStatus.OK, first.status());
        assertEquals("OK", first.code());
        assertEquals("test-epoch", first.epoch());
        assertEquals(1L, first.revision());
        assertEquals(5_000L, first.heartbeatIntervalMs());
        assertEquals(30_000L, first.expireMillis());
        assertEquals(1, fixture.registry.query("svc", null, false).size());

        ApiResult retry = fixture.call(
                HttpMethod.POST,
                NameserverClientApi.REGISTER_PATH,
                registerJson(SESSION_ONE),
                "Bearer " + CLIENT_TOKEN);
        assertEquals(HttpResponseStatus.OK, retry.status());
        assertEquals(1L, retry.revision(), "同 session、同数据的注册重试不能 bump revision");
        assertEquals(1L, fixture.registry.revisionOf("svc"));

        ApiResult heartbeat = fixture.call(
                HttpMethod.POST,
                NameserverClientApi.HEARTBEAT_PATH,
                sessionJson(SESSION_ONE),
                "Bearer " + CLIENT_TOKEN);
        assertEquals(HttpResponseStatus.OK, heartbeat.status());
        assertEquals("OK", heartbeat.code());
        assertEquals(1L, heartbeat.revision(), "正常心跳只续租，不能 bump revision");

        ApiResult unregister = fixture.call(
                HttpMethod.POST,
                NameserverClientApi.UNREGISTER_PATH,
                sessionJson(SESSION_ONE),
                "Bearer " + CLIENT_TOKEN);
        assertEquals(HttpResponseStatus.OK, unregister.status());
        assertEquals("OK", unregister.code());
        assertEquals(2L, unregister.revision());
        assertTrue(fixture.registry.query("svc", null, false).isEmpty());
    }

    @Test
    void heartbeatForMissingInstanceReturnsStable404() {
        Fixture fixture = fixture(true, CLIENT_TOKEN);

        ApiResult result = fixture.call(
                HttpMethod.POST,
                NameserverClientApi.HEARTBEAT_PATH,
                sessionJson(SESSION_ONE),
                "Bearer " + CLIENT_TOKEN);

        assertEquals(HttpResponseStatus.NOT_FOUND, result.status());
        assertEquals("INSTANCE_NOT_FOUND", result.code());
        assertEquals(0L, result.revision());
        assertEquals("test-epoch", result.epoch());
    }

    @Test
    void newSessionTakesOverAndOldSessionCanNeitherHeartbeatNorUnregister() {
        Fixture fixture = fixture(true, CLIENT_TOKEN);
        fixture.call(
                HttpMethod.POST,
                NameserverClientApi.REGISTER_PATH,
                registerJson(SESSION_ONE),
                "Bearer " + CLIENT_TOKEN);

        ApiResult takeover = fixture.call(
                HttpMethod.POST,
                NameserverClientApi.REGISTER_PATH,
                registerJson(SESSION_TWO),
                "Bearer " + CLIENT_TOKEN);
        assertEquals(HttpResponseStatus.OK, takeover.status());
        assertEquals(2L, takeover.revision());

        ApiResult staleHeartbeat = fixture.call(
                HttpMethod.POST,
                NameserverClientApi.HEARTBEAT_PATH,
                sessionJson(SESSION_ONE),
                "Bearer " + CLIENT_TOKEN);
        assertEquals(HttpResponseStatus.CONFLICT, staleHeartbeat.status());
        assertEquals("STALE_SESSION", staleHeartbeat.code());
        assertEquals(2L, staleHeartbeat.revision());

        ApiResult staleUnregister = fixture.call(
                HttpMethod.POST,
                NameserverClientApi.UNREGISTER_PATH,
                sessionJson(SESSION_ONE),
                "Bearer " + CLIENT_TOKEN);
        assertEquals(HttpResponseStatus.CONFLICT, staleUnregister.status());
        assertEquals("STALE_SESSION", staleUnregister.code());
        assertEquals(1, fixture.registry.query("svc", null, false).size());

        ApiResult currentHeartbeat = fixture.call(
                HttpMethod.POST,
                NameserverClientApi.HEARTBEAT_PATH,
                sessionJson(SESSION_TWO),
                "Bearer " + CLIENT_TOKEN);
        assertEquals(HttpResponseStatus.OK, currentHeartbeat.status());
        assertEquals(2L, currentHeartbeat.revision());
    }

    @Test
    void uuidTextCaseIsCanonicalizedToOneSessionOwner() {
        Fixture fixture = fixture(true, CLIENT_TOKEN);
        String upper = "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA";

        ApiResult first = fixture.call(
                HttpMethod.POST,
                NameserverClientApi.REGISTER_PATH,
                registerJson(upper),
                "Bearer " + CLIENT_TOKEN);
        ApiResult retry = fixture.call(
                HttpMethod.POST,
                NameserverClientApi.REGISTER_PATH,
                registerJson(upper.toLowerCase(java.util.Locale.ROOT)),
                "Bearer " + CLIENT_TOKEN);

        assertEquals(HttpResponseStatus.OK, first.status());
        assertEquals(1L, first.revision());
        assertEquals(1L, retry.revision(), "同一 UUID 不能因文本大小写产生伪接管");
    }

    @Test
    void bearerTokenIsRequiredWhenProtocolTokenIsConfigured() {
        Fixture fixture = fixture(true, CLIENT_TOKEN);

        assertUnauthorized(fixture.call(
                HttpMethod.POST,
                NameserverClientApi.REGISTER_PATH,
                registerJson(SESSION_ONE),
                null));
        assertUnauthorized(fixture.call(
                HttpMethod.POST,
                NameserverClientApi.REGISTER_PATH,
                registerJson(SESSION_ONE),
                "Bearer wrong-secret"));
        assertUnauthorized(fixture.call(
                HttpMethod.POST,
                NameserverClientApi.REGISTER_PATH,
                registerJson(SESSION_ONE),
                "Basic " + CLIENT_TOKEN));

        ApiResult accepted = fixture.call(
                HttpMethod.POST,
                NameserverClientApi.REGISTER_PATH,
                registerJson(SESSION_ONE),
                "bearer " + CLIENT_TOKEN);
        assertEquals(HttpResponseStatus.OK, accepted.status());
    }

    @Test
    void malformedJsonWrongMethodUnknownPathAndDisabledApiAreRejected() {
        Fixture enabled = fixture(true, CLIENT_TOKEN);

        ApiResult malformed = enabled.call(
                HttpMethod.POST,
                NameserverClientApi.REGISTER_PATH,
                "{not-json",
                "Bearer " + CLIENT_TOKEN);
        assertEquals(HttpResponseStatus.BAD_REQUEST, malformed.status());
        assertEquals("INVALID_ARGUMENT", malformed.code());

        ApiResult nullBody = enabled.call(
                HttpMethod.POST,
                NameserverClientApi.REGISTER_PATH,
                "null",
                "Bearer " + CLIENT_TOKEN);
        assertEquals(HttpResponseStatus.BAD_REQUEST, nullBody.status());
        assertEquals("INVALID_ARGUMENT", nullBody.code());

        ApiResult wrongMethod = enabled.call(
                HttpMethod.GET,
                NameserverClientApi.REGISTER_PATH,
                registerJson(SESSION_ONE),
                "Bearer " + CLIENT_TOKEN);
        assertEquals(HttpResponseStatus.METHOD_NOT_ALLOWED, wrongMethod.status());
        assertEquals("METHOD_NOT_ALLOWED", wrongMethod.code());
        assertEquals("POST", wrongMethod.allow());

        ApiResult unknownPath = enabled.call(
                HttpMethod.POST,
                NameserverClientApi.PREFIX + "/instances/unknown",
                "{}",
                "Bearer " + CLIENT_TOKEN);
        assertEquals(HttpResponseStatus.NOT_FOUND, unknownPath.status());
        assertEquals("NOT_FOUND", unknownPath.code());

        Fixture disabled = fixture(false, CLIENT_TOKEN);
        ApiResult disabledResult = disabled.call(
                HttpMethod.POST,
                NameserverClientApi.REGISTER_PATH,
                registerJson(SESSION_ONE),
                "Bearer " + CLIENT_TOKEN);
        assertEquals(HttpResponseStatus.NOT_FOUND, disabledResult.status());
        assertEquals("NOT_FOUND", disabledResult.code());
        assertTrue(disabled.registry.query("svc", null, false).isEmpty());
    }

    @Test
    void contentTypeBodyAndIdentityLimitsAreEnforced() {
        Fixture fixture = fixture(true, CLIENT_TOKEN);

        ApiResult wrongMediaType = fixture.call(
                HttpMethod.POST,
                NameserverClientApi.REGISTER_PATH,
                registerJson(SESSION_ONE),
                "Bearer " + CLIENT_TOKEN,
                "text/plain");
        assertEquals(HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE, wrongMediaType.status());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", wrongMediaType.code());

        ApiResult oversized = fixture.call(
                HttpMethod.POST,
                NameserverClientApi.REGISTER_PATH,
                " ".repeat(NameserverClientApi.MAX_BODY_BYTES + 1),
                "Bearer " + CLIENT_TOKEN);
        assertEquals(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, oversized.status());
        assertEquals("PAYLOAD_TOO_LARGE", oversized.code());

        Map<String, Object> invalidIdentity = new LinkedHashMap<>();
        invalidIdentity.put("serviceName", "svc");
        invalidIdentity.put("instanceId", "instance-one");
        invalidIdentity.put("sessionId", "1-1-1-1-1");
        invalidIdentity.put("host", "127.0.0.1");
        invalidIdentity.put("port", 65_536);
        ApiResult invalid = fixture.call(
                HttpMethod.POST,
                NameserverClientApi.REGISTER_PATH,
                JsonCodec.toJson(invalidIdentity),
                "Bearer " + CLIENT_TOKEN);
        assertEquals(HttpResponseStatus.BAD_REQUEST, invalid.status());
        assertEquals("INVALID_ARGUMENT", invalid.code());
        assertTrue(fixture.registry.query("svc", null, false).isEmpty());
    }

    private static void assertUnauthorized(ApiResult result) {
        assertEquals(HttpResponseStatus.UNAUTHORIZED, result.status());
        assertEquals("UNAUTHORIZED", result.code());
    }

    private static Fixture fixture(boolean enabled, String token) {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        NameserverMetricsRegistry metrics = new NameserverMetricsRegistry();
        PushService pushService = new PushService(
                new SubscriptionManager(), true, NameserverGeneration.processLocal(), metrics);
        RegistrationService registrationService = new RegistrationService(registry, pushService, metrics);
        NameserverServerOptions options = NameserverServerOptions.builder()
                .token(token)
                .clientApiEnabled(enabled)
                .build();
        NameserverClientApi api = new NameserverClientApi(
                registrationService, options, () -> "test-epoch", () -> 30_000L);
        return new Fixture(api, registry);
    }

    private static String registerJson(String sessionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serviceName", "svc");
        body.put("instanceId", "instance-one");
        body.put("sessionId", sessionId);
        body.put("host", "127.0.0.1");
        body.put("port", 8080);
        body.put("weight", 100);
        body.put("group", "DEFAULT");
        body.put("zone", "local");
        body.put("metadata", Map.of("version", "1.0.0"));
        return JsonCodec.toJson(body);
    }

    private static String sessionJson(String sessionId) {
        return JsonCodec.toJson(Map.of(
                "serviceName", "svc",
                "instanceId", "instance-one",
                "sessionId", sessionId));
    }

    private record Fixture(NameserverClientApi api, InMemoryServiceRegistry registry) {

        ApiResult call(HttpMethod method, String path, String body, String authorization) {
            return call(method, path, body, authorization, "application/json; charset=UTF-8");
        }

        ApiResult call(
                HttpMethod method,
                String path,
                String body,
                String authorization,
                String contentType) {
            FullHttpRequest request = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    method,
                    path,
                    Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
            request.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
            if (authorization != null) {
                request.headers().set(HttpHeaderNames.AUTHORIZATION, authorization);
            }
            FullHttpResponse response = api.handle(request, path);
            try {
                ResponsePayload payload = JsonCodec.fromJson(
                        response.content().toString(StandardCharsets.UTF_8), ResponsePayload.class);
                return new ApiResult(
                        response.status(),
                        payload.code(),
                        payload.epoch(),
                        payload.revision(),
                        payload.heartbeatIntervalMs(),
                        payload.expireMillis(),
                        response.headers().get(HttpHeaderNames.ALLOW));
            } finally {
                response.release();
                request.release();
            }
        }
    }

    private record ResponsePayload(
            String code,
            String message,
            String epoch,
            long revision,
            long serverTimeMillis,
            long heartbeatIntervalMs,
            long expireMillis) {
    }

    private record ApiResult(
            HttpResponseStatus status,
            String code,
            String epoch,
            long revision,
            long heartbeatIntervalMs,
            long expireMillis,
            String allow) {
    }
}
