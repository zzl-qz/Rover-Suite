package com.rover.agent.core.model;

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
}