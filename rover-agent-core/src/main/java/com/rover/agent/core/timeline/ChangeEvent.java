package com.rover.agent.core.timeline;

import com.rover.agent.core.model.EvidenceEnvelope;
import com.rover.agent.core.model.ResourceScope;
import com.rover.agent.core.model.TimeRange;
import java.util.List;
import java.util.Map;

/** Immutable source event. A polling observation has an interval, never an invented exact change time. */
public record ChangeEvent(String eventId, ResourceScope scope, String controlTargetId, String sourceId,
                          String sourceEventId, String kind, Long occurredAtMillis, long observedAtMillis,
                          TimeRange occurrenceRange, Subject subject, Map<String, Object> facts,
                          List<String> limitations) {
    public ChangeEvent {
        if (blank(eventId) || scope == null || blank(controlTargetId) || blank(sourceId)
                || blank(sourceEventId) || blank(kind) || subject == null || occurrenceRange == null
                || occurrenceRange.isUnspecified() || observedAtMillis <= 0
                || (occurredAtMillis != null && !occurrenceRange.contains(occurredAtMillis))) {
            throw new IllegalArgumentException("变更事件身份、范围或时间不合法");
        }
        // Reuse the evidence contract's JSON validation and deep immutable copy.
        facts = immutable(facts);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    private static Map<String, Object> immutable(Map<String, Object> facts) {
        return new EvidenceEnvelope(1, "timeline", "EVENT", "", "timeline-v1", null, null, null,
                1, null, "SOURCE_EVENTS", 1, null, null, List.of(), facts, List.of(), null, null,
                "timeline-allowlist-v1").facts();
    }

    private static boolean blank(String value) { return value == null || value.isBlank() || value.length() > 255; }

    public Map<String, Object> json() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("eventId", eventId); result.put("sourceId", sourceId); result.put("sourceEventId", sourceEventId);
        result.put("scope", Map.of("organizationId", scope.organizationId(), "teamId", scope.teamId(), "environmentId", scope.environmentId()));
        result.put("controlTargetId", controlTargetId); result.put("kind", kind); result.put("occurredAtMillis", occurredAtMillis);
        result.put("observedAtMillis", observedAtMillis); result.put("occurrenceRange", Map.of("fromMillis", occurrenceRange.fromMillis(), "toMillis", occurrenceRange.toMillis()));
        Map<String, Object> resource = new java.util.LinkedHashMap<>();
        resource.put("routeId", subject.routeId()); resource.put("businessPrefix", subject.businessPrefix());
        resource.put("routeUid", subject.routeUid()); resource.put("generation", subject.generation()); resource.put("services", subject.services());
        resource.put("group", subject.group()); resource.put("instanceId", subject.instanceId()); resource.put("hostPort", subject.hostPort()); resource.put("component", subject.component());
        result.put("subject", resource); result.put("facts", facts); result.put("limitations", limitations);
        return java.util.Collections.unmodifiableMap(result);
    }

    /** Identity supplied by the source; missing UID stays missing. group is not an artifact or a release ID. */
    public record Subject(String routeId, String businessPrefix, String routeUid, long generation,
                          List<String> services, String group, String instanceId, String hostPort, String component) {
        public Subject {
            services = services == null ? List.of() : services;
            for (String value : List.of(orEmpty(routeId), orEmpty(businessPrefix), orEmpty(routeUid),
                    orEmpty(group), orEmpty(instanceId), orEmpty(hostPort), orEmpty(component))) {
                if (value.length() > 255 || value.chars().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("资源标识过长或包含控制字符");
                }
            }
            if (generation < 0 || services.size() > 100 || services.stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 255 || s.chars().anyMatch(Character::isISOControl))) {
                throw new IllegalArgumentException("服务关联不合法");
            }
            services = services.stream().distinct().sorted().toList();
        }
        private static String orEmpty(String text) { return text == null ? "" : text; }
    }
}
