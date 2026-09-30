package com.rover.agent.core.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 一条只读证据：来自某个只读数据源的观测事实，可追溯到来源。
 *
 * {@code metadata} 至少含统计口径（{@code windowSeconds}、{@code sampleSize}、{@code observedAtMillis}），
 * 「样本不足」的结论必须能从证据本身看出来。
 */
public record Evidence(String evidenceId, String taskId, EvidenceType type, String source, String title,
                       String summary, String rawReference, Map<String, String> metadata, long observedAtMillis) {

    /** 产生这条证据的模型工具调用号。规划路径没有工具调用时，这个键不存在。 */
    public static final String KEY_TOOL_CALL_ID = "toolCallId";

    /** 采集一条证据；ID 在此生成。 */
    public static Evidence of(String taskId, EvidenceType type, String source, String title, String summary,
                              String rawReference, long observedAtMillis) {
        return of(taskId, type, source, title, summary, rawReference, Map.of(), observedAtMillis);
    }

    /** 采集一条带结构化补充的证据；{@code metadata} 为空时按空 Map 处理，取值一律不能为 null。 */
    public static Evidence of(String taskId, EvidenceType type, String source, String title, String summary,
                              String rawReference, Map<String, String> metadata, long observedAtMillis) {
        return new Evidence(UUID.randomUUID().toString(), taskId, type, source, title, summary, rawReference,
                metadata == null ? Map.of() : Map.copyOf(metadata), observedAtMillis);
    }

    /** 模型这次工具调用的 id；没有绑定时为空串。 */
    public String toolCallId() {
        if (metadata == null) {
            return "";
        }
        String id = metadata.get(KEY_TOOL_CALL_ID);
        return id == null ? "" : id;
    }

    /** 记上工具调用号。空号或已经是这个号时原样返回，避免无意义复制。 */
    public Evidence withToolCallId(String toolCallId) {
        if (toolCallId == null || toolCallId.isBlank() || toolCallId.equals(toolCallId())) {
            return this;
        }
        Map<String, String> next = new LinkedHashMap<>(metadata);
        next.put(KEY_TOOL_CALL_ID, toolCallId);
        return new Evidence(evidenceId, taskId, type, source, title, summary, rawReference,
                Map.copyOf(next), observedAtMillis);
    }
}
