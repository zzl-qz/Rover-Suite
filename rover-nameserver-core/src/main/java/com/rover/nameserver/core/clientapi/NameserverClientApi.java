package com.rover.nameserver.core.clientapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.rover.common.constants.HttpConstants;
import com.rover.common.constants.NameserverConstants;
import com.rover.common.json.JsonCodec;
import com.rover.common.security.TokenAuth;
import com.rover.common.protocol.RegisterRequest;
import com.rover.nameserver.core.registration.RegistrationOwner;
import com.rover.nameserver.core.registration.RegistrationResult;
import com.rover.nameserver.core.registration.RegistrationService;
import com.rover.nameserver.core.server.NameserverServerOptions;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-17 00:00:00
 * Description: 面向非 Java 服务提供方的轻量 HTTP+JSON 注册 API，仅负责注册、心跳和注销
 */
@Slf4j
public final class NameserverClientApi {

    public static final String PREFIX = "/v1/client";
    public static final String REGISTER_PATH = PREFIX + "/instances/register";
    public static final String HEARTBEAT_PATH = PREFIX + "/instances/heartbeat";
    public static final String UNREGISTER_PATH = PREFIX + "/instances/unregister";

    static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int MAX_SERVICE_NAME_LENGTH = 128;
    private static final int MAX_INSTANCE_ID_LENGTH = 256;
    private static final int MAX_HOST_LENGTH = 255;
    private static final int MAX_GROUP_LENGTH = 128;
    private static final int MAX_ZONE_LENGTH = 128;
    private static final int MAX_METADATA_ENTRIES = 64;
    private static final int MAX_METADATA_KEY_LENGTH = 128;
    private static final int MAX_METADATA_VALUE_LENGTH = 1024;

    private final RegistrationService registrationService;
    private final NameserverServerOptions options;
    private final Supplier<String> epochSupplier;
    private final LongSupplier expireMillisSupplier;

    public NameserverClientApi(
            RegistrationService registrationService,
            NameserverServerOptions options,
            Supplier<String> epochSupplier,
            LongSupplier expireMillisSupplier) {
        this.registrationService = java.util.Objects.requireNonNull(registrationService, "registrationService");
        this.options = java.util.Objects.requireNonNull(options, "options");
        this.epochSupplier = java.util.Objects.requireNonNull(epochSupplier, "epochSupplier");
        this.expireMillisSupplier = java.util.Objects.requireNonNull(
                expireMillisSupplier, "expireMillisSupplier");
    }

    public boolean supports(String path) {
        return path != null && (PREFIX.equals(path) || path.startsWith(PREFIX + "/"));
    }

    /**
     * 处理一条 Client API 请求并返回完整响应。方法不持有 request，调用方仍负责其引用计数。
     */
    public FullHttpResponse handle(FullHttpRequest request, String path) {
        if (!options.isClientApiEnabled()) {
            return error(HttpResponseStatus.NOT_FOUND, "NOT_FOUND", "HTTP Client API 未启用");
        }
        if (!HttpMethod.POST.equals(request.method())) {
            FullHttpResponse response = error(
                    HttpResponseStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "仅支持 POST");
            response.headers().set(HttpHeaderNames.ALLOW, HttpMethod.POST.name());
            return response;
        }
        if (!authorized(request)) {
            return error(HttpResponseStatus.UNAUTHORIZED, "UNAUTHORIZED", "鉴权失败");
        }
        String contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE);
        if (!isJsonContentType(contentType)) {
            return error(
                    HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE,
                    "UNSUPPORTED_MEDIA_TYPE",
                    "Content-Type 必须是 application/json");
        }
        if (request.content().readableBytes() > MAX_BODY_BYTES) {
            return error(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE,
                    "PAYLOAD_TOO_LARGE", "请求体不能超过 " + MAX_BODY_BYTES + " 字节");
        }

        try {
            String body = request.content().toString(StandardCharsets.UTF_8);
            return switch (path) {
                case REGISTER_PATH -> register(JsonCodec.fromJson(body, ClientRegisterRequest.class));
                case HEARTBEAT_PATH -> heartbeat(JsonCodec.fromJson(body, ClientSessionRequest.class));
                case UNREGISTER_PATH -> unregister(JsonCodec.fromJson(body, ClientSessionRequest.class));
                default -> error(HttpResponseStatus.NOT_FOUND, "NOT_FOUND", "unknown client path: " + path);
            };
        } catch (IllegalArgumentException ex) {
            return error(HttpResponseStatus.BAD_REQUEST, "INVALID_ARGUMENT", safeMessage(ex, "请求参数错误"));
        } catch (Exception ex) {
            log.warn("Nameserver Client API error, path={}", path, ex);
            return error(HttpResponseStatus.INTERNAL_SERVER_ERROR, "SERVER_ERROR", "client api error");
        }
    }

    private FullHttpResponse register(ClientRegisterRequest body) {
        requireBody(body);
        String serviceName = required(body.serviceName(), "serviceName", MAX_SERVICE_NAME_LENGTH);
        String instanceId = required(body.instanceId(), "instanceId", MAX_INSTANCE_ID_LENGTH);
        String sessionId = validSessionId(body.sessionId());
        String host = required(body.host(), "host", MAX_HOST_LENGTH);
        if (body.port() <= 0 || body.port() > 65535) {
            throw new IllegalArgumentException("port 必须在 1-65535 之间");
        }
        String group = optional(body.group(), "group", MAX_GROUP_LENGTH);
        String zone = optional(body.zone(), "zone", MAX_ZONE_LENGTH);
        Map<String, String> metadata = validMetadata(body.metadata());

        RegisterRequest request = new RegisterRequest();
        request.setServiceName(serviceName);
        request.setInstanceId(instanceId);
        request.setHost(host);
        request.setPort(body.port());
        request.setWeight(body.weight() <= 0 ? 100 : body.weight());
        request.setGroup(group);
        request.setZone(zone);
        request.setEphemeral(true);
        request.setMetadata(metadata);

        RegistrationResult result = registrationService.register(request, RegistrationOwner.http(sessionId));
        return resultResponse(result);
    }

    private FullHttpResponse heartbeat(ClientSessionRequest body) {
        requireBody(body);
        String serviceName = required(body.serviceName(), "serviceName", MAX_SERVICE_NAME_LENGTH);
        String instanceId = required(body.instanceId(), "instanceId", MAX_INSTANCE_ID_LENGTH);
        String sessionId = validSessionId(body.sessionId());
        RegistrationResult result = registrationService.heartbeat(
                serviceName, instanceId, RegistrationOwner.http(sessionId));
        return resultResponse(result);
    }

    private FullHttpResponse unregister(ClientSessionRequest body) {
        requireBody(body);
        String serviceName = required(body.serviceName(), "serviceName", MAX_SERVICE_NAME_LENGTH);
        String instanceId = required(body.instanceId(), "instanceId", MAX_INSTANCE_ID_LENGTH);
        String sessionId = validSessionId(body.sessionId());
        RegistrationResult result = registrationService.unregister(
                serviceName, instanceId, RegistrationOwner.http(sessionId));
        return resultResponse(result);
    }

    private FullHttpResponse resultResponse(RegistrationResult result) {
        if (result.isAccepted()) {
            return response(HttpResponseStatus.OK, "OK", "OK", result.revision());
        }
        if (result.isNotFound()) {
            return response(HttpResponseStatus.NOT_FOUND,
                    "INSTANCE_NOT_FOUND", "实例不存在，请先注册", result.revision());
        }
        if (result.isOwnerMismatch()) {
            return response(HttpResponseStatus.CONFLICT,
                    "STALE_SESSION", "实例已由其他 session 接管", result.revision());
        }
        return response(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                "SERVER_ERROR", "未知注册状态", result.revision());
    }

    private FullHttpResponse error(HttpResponseStatus status, String code, String message) {
        return response(status, code, message, 0L);
    }

    private FullHttpResponse response(
            HttpResponseStatus status, String code, String message, long revision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message);
        payload.put("epoch", safeEpoch());
        payload.put("revision", revision);
        payload.put("serverTimeMillis", System.currentTimeMillis());
        payload.put("heartbeatIntervalMs", NameserverConstants.DEFAULT_CLIENT_REPORT_INTERVAL_MILLIS);
        payload.put("expireMillis", expireMillisSupplier.getAsLong());

        byte[] bytes = JsonCodec.toJson(payload).getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpConstants.MEDIA_TYPE_JSON_UTF8);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        return response;
    }

    private boolean authorized(FullHttpRequest request) {
        String expected = options.getToken();
        if (TokenAuth.isBlank(expected)) {
            return true;
        }
        String header = request.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        return TokenAuth.matches(expected, header.substring(7).trim());
    }

    private String safeEpoch() {
        String epoch = epochSupplier.get();
        return epoch == null ? "" : epoch;
    }

    private static String required(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(name + " 长度不能超过 " + maxLength);
        }
        return trimmed;
    }

    private static void requireBody(Object body) {
        if (body == null) {
            throw new IllegalArgumentException("请求体不能为 null");
        }
    }

    private static String optional(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(name + " 长度不能超过 " + maxLength);
        }
        return trimmed;
    }

    private static String validSessionId(String value) {
        String sessionId = required(value, "sessionId", 36);
        try {
            String canonical = UUID.fromString(sessionId).toString();
            if (!canonical.equalsIgnoreCase(sessionId)) {
                throw new IllegalArgumentException("sessionId 必须是标准 UUID");
            }
            return canonical;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("sessionId 必须是 UUID", ex);
        }
    }

    private static boolean isJsonContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        int parameterStart = contentType.indexOf(';');
        String mediaType = parameterStart < 0
                ? contentType
                : contentType.substring(0, parameterStart);
        return HttpConstants.MEDIA_TYPE_JSON.equalsIgnoreCase(mediaType.trim());
    }

    private static Map<String, String> validMetadata(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return new HashMap<>();
        }
        if (source.size() > MAX_METADATA_ENTRIES) {
            throw new IllegalArgumentException("metadata 不能超过 " + MAX_METADATA_ENTRIES + " 项");
        }
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = required(entry.getKey(), "metadata key", MAX_METADATA_KEY_LENGTH);
            String value = entry.getValue();
            if (value == null) {
                throw new IllegalArgumentException("metadata value 不能为空");
            }
            if (value.length() > MAX_METADATA_VALUE_LENGTH) {
                throw new IllegalArgumentException(
                        "metadata value 长度不能超过 " + MAX_METADATA_VALUE_LENGTH);
            }
            result.put(key, value);
        }
        return result;
    }

    private static String safeMessage(Exception ex, String fallback) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? fallback : ex.getMessage();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClientRegisterRequest(
            String serviceName,
            String instanceId,
            String sessionId,
            String host,
            int port,
            int weight,
            String group,
            String zone,
            Map<String, String> metadata) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClientSessionRequest(String serviceName, String instanceId, String sessionId) {
    }
}
