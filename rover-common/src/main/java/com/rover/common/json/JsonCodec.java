package com.rover.common.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 基于 Jackson 的 JSON 编解码工具。
 *
 * 这个类是什么：管理口与 overlay 文件共用的 JSON 序列化/反序列化统一入口。
 * 核心职责：①把任意对象/集合序列化为 JSON 字符串；②解析 JSON 对象数组为字符串行；
 * ③解析管理口 key/value 请求体（JSON 或表单）。
 * 被谁用：AbstractManageApi、RouteOverlayStore、RuntimeConfigOverlayStore 等。
 */
public final class JsonCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 落盘用：开缩进，人眼可读；读的时候不挑格式。 */
    private static final ObjectMapper PRETTY_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private JsonCodec() {
    }

    /**
     * 序列化任意对象为 JSON 字符串（紧凑，适合 HTTP 响应）。
     *
     * @param value 待序列化对象（Map/List/POJO/标量）
     * @return JSON 字符串
     * @throws IllegalStateException 序列化失败
     */
    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("JSON 序列化失败", ex);
        }
    }

    /**
     * 带缩进的 JSON，给 overlay 配置文件用。
     * 等价于 ObjectMapper 打开 SerializationFeature.INDENT_OUTPUT。
     */
    public static String toPrettyJson(Object value) {
        try {
            return PRETTY_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("JSON 序列化失败", ex);
        }
    }

    /**
     * 解析管理口 key/value 请求体，支持 JSON 对象或 urlencoded 表单。
     *
     * @param body        请求体
     * @param contentType Content-Type 头，可为 null
     * @return [key, value]；body 为空或缺少 key 时返回 null
     */
    public static String[] parseKeyValue(String body, String contentType) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String trimmed = body.trim();
        if ((contentType != null && contentType.toLowerCase().contains("application/json"))
                || trimmed.startsWith("{")) {
            try {
                JsonNode node = MAPPER.readTree(trimmed);
                JsonNode keyNode = node.get("key");
                if (keyNode == null || keyNode.isNull()) {
                    return null;
                }
                String value = "";
                JsonNode valueNode = node.get("value");
                if (valueNode != null && !valueNode.isNull()) {
                    value = valueNode.asText();
                }
                return new String[]{keyNode.asText(), value};
            } catch (JsonProcessingException ex) {
                return null;
            }
        }
        String key = null;
        String value = null;
        for (String part : trimmed.split("&")) {
            int idx = part.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String name = urlDecode(part.substring(0, idx));
            String raw = urlDecode(part.substring(idx + 1));
            if ("key".equals(name)) {
                key = raw;
            } else if ("value".equals(name)) {
                value = raw;
            }
        }
        if (key == null) {
            return null;
        }
        return new String[]{key, value == null ? "" : value};
    }

    /**
     * 解析 JSON 对象数组为字符串键值对行列表。
     *
     * @param json JSON 数组字符串
     * @return 键值对行列表；空串返回空列表
     * @throws IllegalArgumentException 解析失败
     */
    public static List<Map<String, String>> parseStringMapArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, String>>>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("JSON 数组解析失败", ex);
        }
    }

    private static String urlDecode(String raw) {
        try {
            return java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return raw;
        }
    }
}
