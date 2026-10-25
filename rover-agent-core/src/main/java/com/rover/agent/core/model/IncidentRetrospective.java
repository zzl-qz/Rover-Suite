package com.rover.agent.core.model;

import java.util.List;

/** Structured, human-reviewable incident learning proposal. */
public record IncidentRetrospective(String documentId, String incidentId, ResourceScope scope, String title,
        String summary, String rootCause, String resolution, String outcome, List<String> failureModes,
        String createdBy, long createdAtMillis, long expiresAtMillis) {
    public IncidentRetrospective {
        if (documentId == null || !documentId.matches("case-[A-Za-z0-9._-]{1,59}")
                || incidentId == null || incidentId.isBlank() || incidentId.length() > 64 || scope == null
                || title == null || title.isBlank() || title.length() > 200
                || summary == null || summary.isBlank() || summary.length() > 1000
                || rootCause == null || rootCause.isBlank() || rootCause.length() > 2000
                || resolution == null || resolution.isBlank() || resolution.length() > 2000
                || outcome == null || outcome.isBlank() || outcome.length() > 1000
                || createdBy == null || createdBy.isBlank() || createdBy.length() > 128
                || createdAtMillis < 1 || expiresAtMillis <= createdAtMillis) {
            throw new IllegalArgumentException("案例复盘字段无效");
        }
        failureModes = failureModes == null ? List.of() : List.copyOf(failureModes);
        if (failureModes.size() > 50 || failureModes.stream().anyMatch(mode -> mode == null || mode.isBlank() || mode.length() > 100)) {
            throw new IllegalArgumentException("失败经验条目无效");
        }
    }
}
