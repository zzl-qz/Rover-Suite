package com.rover.agent.core.model;

/** Current deduplication window for one source-provided alert fingerprint. */
public record AlertLifecycle(String sourceId, String alertKey, boolean active, String path, long generation,
                             String sessionId, String incidentId, String taskId, AlertEventState lastState,
                             String lastPayloadFingerprint, long updatedAtMillis) {
    public AlertLifecycle {
        if (sourceId == null || sourceId.isBlank() || sourceId.length() > 128
                || alertKey == null || alertKey.isBlank() || alertKey.length() > 256
                || path == null || path.isBlank() || path.length() > 512
                || generation < 0 || updatedAtMillis <= 0
                || lastPayloadFingerprint != null && !lastPayloadFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("alert lifecycle is invalid");
        }
        sourceId = sourceId.trim();
        alertKey = alertKey.trim();
        path = path.trim();
    }
}
