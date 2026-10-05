package com.rover.agent.runtime.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 通过括号配对提取模型输出的 JSON 对象，并读取标量或标量数组。
 * 格式不符时返回空值，业务校验由调用方处理。
 */
public final class ModelJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelJson() { }

    /**
     * 取出输出里第一个能解析成对象的 JSON 片段；取不到时返回空。
     *
     * 逐个候选起点尝试：跳过被说明文字带出来的花括号，也跳过写坏的片段去够后面那个完整的对象。
     */
    public static Optional<JsonNode> object(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.trim();
        int start = text.indexOf('{');
        while (start >= 0) {
            int end = endOfObject(text, start);
            if (end > start) {
                try {
                    JsonNode node = MAPPER.readTree(text.substring(start, end + 1));
                    if (node != null && node.isObject()) {
                        return Optional.of(node);
                    }
                } catch (Exception ignored) {
                    // 这一段不是合法 JSON：继续找下一个 '{'
                }
            }
            start = text.indexOf('{', start + 1);
        }
        return Optional.empty();
    }

    /** 从 {@code start} 处的 '{' 开始做括号配对，返回配对成功的 '}' 下标；不配对时返回 -1。字符串与转义不参与计数。 */
    private static int endOfObject(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 取标量字段的文本；缺失、为 null 或非标量时返回空串。 */
    public static String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) {
            return "";
        }
        return value.asText("").trim();
    }

    /** 取字符串数组字段；缺失或类型不符时返回空列表，元素会去空白并丢弃空串。 */
    public static List<String> strings(JsonNode node, String field) {
        if (node == null) {
            return List.of();
        }
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : value) {
            if (item == null || !item.isValueNode()) {
                continue;
            }
            String text = item.asText("").trim();
            if (!text.isBlank()) {
                values.add(text);
            }
        }
        return List.copyOf(values);
    }

    /** 取对象数组字段；缺失或类型不符时返回空列表。 */
    public static List<JsonNode> objects(JsonNode node, String field) {
        if (node == null) {
            return List.of();
        }
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        List<JsonNode> values = new ArrayList<>();
        for (JsonNode item : value) {
            if (item != null && item.isObject()) {
                values.add(item);
            }
        }
        return List.copyOf(values);
    }
}