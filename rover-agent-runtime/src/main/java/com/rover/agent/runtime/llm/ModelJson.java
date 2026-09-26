package com.rover.agent.runtime.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 模型 JSON 输出的容错解析。
 *
 * 模型输出不可完全信任，因此解析只做两件事：从可能的代码块/说明文字里取出对象，
 * 再按字段名取标量或标量数组。任何不符合预期的地方都返回空值，由调用方回退到确定性逻辑——
 * 解析失败不会让任务失败，只会让本次少一份模型建议。
 */
public final class ModelJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelJson() { }

    /** 取出输出里的第一个 JSON 对象；取不到或不是对象时返回空。 */
    public static Optional<JsonNode> object(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(text.substring(start, end + 1));
            return node != null && node.isObject() ? Optional.of(node) : Optional.empty();
        } catch (Exception ex) {
            return Optional.empty();
        }
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