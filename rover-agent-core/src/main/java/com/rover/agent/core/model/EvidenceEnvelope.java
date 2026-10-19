package com.rover.agent.core.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 版本化、不可变取证契约；旧记录只能映射为 PARTIAL，不能补造历史身份或完整覆盖。 */
public record EvidenceEnvelope(int schemaVersion, String sourceId, String sourceKind, String sourceQueryId,
                               String queryTemplateVersion, TimeRange requestedRange, TimeRange eventTimeRange,
                               TimeRange coverageRange, long collectedAtMillis, Long validUntilMillis,
                               String samplingMode, long sampleCount, EvidenceQuality qualityStatus,
                               InvestigationContext context, List<ResourceRef> resourceRefs,
                               Map<String, Object> facts, List<String> limitations, String sourceRevision,
                               String payloadDigest, String redactionPolicyVersion) {
    public EvidenceEnvelope {
        if (schemaVersion != 1 || sampleCount < 0) { throw new IllegalArgumentException("无效证据契约"); }
        requestedRange = requestedRange == null ? TimeRange.unspecified() : requestedRange;
        resourceRefs = resourceRefs == null ? List.of() : List.copyOf(resourceRefs);
        facts = freeze(facts == null ? Map.of() : facts);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        qualityStatus = qualityStatus == null ? EvidenceQuality.PARTIAL : qualityStatus;
        String computed = digest(facts);
        if (payloadDigest != null && !payloadDigest.equals(computed)) { throw new IllegalArgumentException("证据事实摘要不匹配"); }
        payloadDigest = computed;
    }

    public static EvidenceEnvelope legacy(String source, String kind, String query, String summary,
                                           Map<String, String> metadata, long at) {
        long count = number(metadata, "sampleSize");
        return new EvidenceEnvelope(1, source, kind, query, "legacy-v1", null, null, null, at, null,
                "UNKNOWN_LEGACY", Math.max(0, count), EvidenceQuality.PARTIAL, null, List.of(),
                Map.of("summary", summary == null ? "" : summary),
                List.of("旧数据源未提供完整覆盖、采样及资源身份；不能推定历史状态。"), null, null, "legacy-unknown");
    }

    public EvidenceEnvelope inContext(InvestigationContext queryContext, List<ResourceRef> refs) {
        return new EvidenceEnvelope(schemaVersion, sourceId, sourceKind, sourceQueryId, queryTemplateVersion,
                queryContext.requestedRange().isUnspecified() ? requestedRange : queryContext.requestedRange(),
                eventTimeRange, coverageRange, collectedAtMillis, validUntilMillis,
                samplingMode, sampleCount, qualityStatus, queryContext, refs, facts, limitations, sourceRevision,
                payloadDigest, redactionPolicyVersion);
    }

    public EvidenceEnvelope requested(TimeRange range, boolean currentOnly) {
        List<String> bounds = new ArrayList<>(limitations);
        if (currentOnly) { bounds.add("当前快照不覆盖指定历史时间范围。"); }
        return new EvidenceEnvelope(schemaVersion, sourceId, sourceKind, sourceQueryId, queryTemplateVersion,
                range, eventTimeRange, coverageRange, collectedAtMillis, validUntilMillis, samplingMode, sampleCount,
                currentOnly ? EvidenceQuality.OUT_OF_RANGE : qualityStatus, context, resourceRefs, facts, bounds,
                sourceRevision, payloadDigest, redactionPolicyVersion);
    }

    private static long number(Map<String, String> data, String key) {
        try { return Long.parseLong(data == null ? "0" : data.getOrDefault(key, "0")); }
        catch (NumberFormatException ex) { return 0; }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> freeze(Map<String, Object> input) {
        Map<String, Object> result = new TreeMap<>();
        input.forEach((key, value) -> result.put(key, immutable(value)));
        return Collections.unmodifiableMap(result);
    }

    @SuppressWarnings("unchecked")
    private static Object immutable(Object value) {
        if (value instanceof Map<?, ?> map) { return freeze((Map<String, Object>) map); }
        if (value instanceof List<?> list) { return Collections.unmodifiableList(list.stream().map(EvidenceEnvelope::immutable).toList()); }
        if (value instanceof Number number) { return new java.math.BigDecimal(number.toString()).stripTrailingZeros(); }
        if (value == null || value instanceof String || value instanceof Boolean) { return value; }
        throw new IllegalArgumentException("证据事实仅允许 JSON 基础类型");
    }

    private static String digest(Map<String, Object> value) {
        try {
            // 树有序化后采用带类型、长度的编码，避免分隔符或 Map 插入顺序造成歧义。
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical(value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static String canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("m");
            map.forEach((key, child) -> out.append(canonical(key)).append(canonical(child)));
            return out.append(';').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder("l"); list.forEach(v -> out.append(canonical(v)));
            return out.append(';').toString();
        }
        String text = String.valueOf(value);
        return (value == null ? "z" : value instanceof String ? "s" : value instanceof Boolean ? "b" : "n")
                + text.length() + ":" + text;
    }
}
