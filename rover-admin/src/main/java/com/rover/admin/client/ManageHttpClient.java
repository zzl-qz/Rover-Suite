package com.rover.admin.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:50:00
 * Description: 调组件 /_manage 接口
 */
@Component
public class ManageHttpClient {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final ObjectMapper objectMapper;

    public ManageHttpClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode getJson(String baseUrl, String path) throws IOException, InterruptedException {
        return send(baseUrl, path, "GET", null, null);
    }

    public List<Map<String, Object>> getList(String baseUrl, String path)
            throws IOException, InterruptedException {
        HttpResponse<String> response = raw(baseUrl, path, "GET", null, null);
        ensureOk(response);
        return objectMapper.readValue(response.body(), new TypeReference<>() {
        });
    }

    public Map<String, Object> postConfig(String baseUrl, String key, String value)
            throws IOException, InterruptedException {
        String body = "key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "&value=" + URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
        HttpResponse<String> response = raw(
                baseUrl, "/_manage/configs", "POST", body, "application/x-www-form-urlencoded");
        ensureOk(response);
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    public Map<String, Object> putJson(String baseUrl, String path, Object payload)
            throws IOException, InterruptedException {
        String body = objectMapper.writeValueAsString(payload);
        HttpResponse<String> response = raw(baseUrl, path, "PUT", body, "application/json");
        ensureOk(response);
        return objectMapper.readValue(response.body(), new TypeReference<>() {
        });
    }

    public Map<String, Object> postJson(String baseUrl, String path, Object payload)
            throws IOException, InterruptedException {
        String body = objectMapper.writeValueAsString(payload);
        HttpResponse<String> response = raw(baseUrl, path, "POST", body, "application/json");
        ensureOk(response);
        return objectMapper.readValue(response.body(), new TypeReference<>() {
        });
    }

    public Map<String, Object> delete(String baseUrl, String path)
            throws IOException, InterruptedException {
        HttpResponse<String> response = raw(baseUrl, path, "DELETE", null, null);
        ensureOk(response);
        return objectMapper.readValue(response.body(), new TypeReference<>() {
        });
    }

    private JsonNode send(String baseUrl, String path, String method, String body, String contentType)
            throws IOException, InterruptedException {
        HttpResponse<String> response = raw(baseUrl, path, method, body, contentType);
        ensureOk(response);
        return objectMapper.readTree(response.body());
    }

    private HttpResponse<String> raw(
            String baseUrl, String path, String method, String body, String contentType)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(trimSlash(baseUrl) + path))
                .timeout(Duration.ofSeconds(5));
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        if ("GET".equals(method)) {
            builder.GET();
        } else if ("DELETE".equals(method)) {
            builder.DELETE();
        } else if ("PUT".equals(method)) {
            builder.PUT(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        } else {
            builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void ensureOk(HttpResponse<String> response) {
        if (response.statusCode() < 400) {
            return;
        }
        String message = response.body();
        try {
            JsonNode node = objectMapper.readTree(message);
            if (node.has("message")) {
                message = node.get("message").asText();
            }
        } catch (Exception ignored) {
            // 用原始 body
        }
        throw new IllegalStateException(message);
    }

    private static String trimSlash(String url) {
        if (url == null) {
            return "";
        }
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}
