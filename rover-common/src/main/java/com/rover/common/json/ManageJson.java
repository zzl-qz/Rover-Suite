package com.rover.common.json;

import com.rover.common.config.ConfigApplyMode;
import com.rover.common.config.ConfigItem;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:40:00
 * Description: 管理口用的轻量 JSON 拼装，不引入额外依赖
 */
public final class ManageJson {

    private ManageJson() {
    }

    public static String object(Map<String, Object> fields) {
        StringBuilder sb = new StringBuilder(128);
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(entry.getKey())).append("\":");
            appendValue(sb, entry.getValue());
        }
        sb.append('}');
        return sb.toString();
    }

    public static String configItems(List<ConfigItem> items) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('[');
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            ConfigItem item = items.get(i);
            sb.append('{')
                    .append("\"key\":\"").append(escape(item.getKey())).append("\",")
                    .append("\"value\":\"").append(escape(item.getValue())).append("\",")
                    .append("\"defaultValue\":\"").append(escape(item.getDefaultValue())).append("\",")
                    .append("\"description\":\"").append(escape(item.getDescription())).append("\",")
                    .append("\"applyMode\":\"").append(escape(modeName(item.getApplyMode()))).append("\",")
                    .append("\"sensitive\":").append(item.isSensitive()).append(',')
                    .append("\"hotReloadable\":").append(item.isHotReloadable())
                    .append('}');
        }
        sb.append(']');
        return sb.toString();
    }

    public static String arrayOfObjects(Collection<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('[');
        boolean first = true;
        for (Map<String, Object> row : rows) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(object(row));
        }
        sb.append(']');
        return sb.toString();
    }

    /** 解析 {"key":"...","value":"..."} 或 key=value 表单 */
    public static String[] parseKeyValue(String body, String contentType) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String trimmed = body.trim();
        if ((contentType != null && contentType.toLowerCase().contains("application/json"))
                || trimmed.startsWith("{")) {
            String key = extractJsonString(trimmed, "key");
            String value = extractJsonString(trimmed, "value");
            if (key == null) {
                return null;
            }
            return new String[]{key, value == null ? "" : value};
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

    private static void appendValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            sb.append(object(typed));
        } else if (value instanceof Collection<?> collection) {
            sb.append('[');
            boolean first = true;
            for (Object item : collection) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendValue(sb, item);
            }
            sb.append(']');
        } else {
            sb.append('"').append(escape(String.valueOf(value))).append('"');
        }
    }

    private static String modeName(ConfigApplyMode mode) {
        return mode == null ? "" : mode.name();
    }

    private static String extractJsonString(String json, String field) {
        String needle = "\"" + field + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) {
            return null;
        }
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) {
            return null;
        }
        int start = json.indexOf('"', colon + 1);
        if (start < 0) {
            return null;
        }
        StringBuilder value = new StringBuilder();
        for (int i = start + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                value.append(json.charAt(i + 1));
                i++;
                continue;
            }
            if (c == '"') {
                return value.toString();
            }
            value.append(c);
        }
        return null;
    }

    private static String urlDecode(String raw) {
        try {
            return java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return raw;
        }
    }

    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    /** 解析 JSON 对象数组，每项取指定字符串字段 */
    public static List<Map<String, String>> parseObjectArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        String trimmed = json.trim();
        if (!trimmed.startsWith("[")) {
            throw new IllegalArgumentException("需要 JSON 数组");
        }
        List<Map<String, String>> rows = new ArrayList<>();
        int i = 1;
        while (i < trimmed.length()) {
            while (i < trimmed.length() && Character.isWhitespace(trimmed.charAt(i))) {
                i++;
            }
            if (i < trimmed.length() && trimmed.charAt(i) == ']') {
                break;
            }
            if (trimmed.charAt(i) != '{') {
                throw new IllegalArgumentException("数组元素必须是对象");
            }
            int end = findMatchingBrace(trimmed, i);
            String obj = trimmed.substring(i, end + 1);
            Map<String, String> row = new LinkedHashMap<>();
            for (String field : List.of(
                    "key",
                    "value",
                    "id",
                    "businessPrefix",
                    "targetUrl",
                    "serviceName",
                    "group",
                    "stripPrefix")) {
                String value = extractJsonString(obj, field);
                if (value != null) {
                    row.put(field, value);
                }
            }
            rows.add(row);
            i = end + 1;
            while (i < trimmed.length() && (Character.isWhitespace(trimmed.charAt(i)) || trimmed.charAt(i) == ',')) {
                i++;
            }
        }
        return rows;
    }

    private static int findMatchingBrace(String text, int start) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new IllegalArgumentException("JSON 对象括号不匹配");
    }
}
