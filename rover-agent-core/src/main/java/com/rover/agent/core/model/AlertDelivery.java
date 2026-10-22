package com.rover.agent.core.model;

/** Immutable idempotency receipt for one alert webhook delivery. */
public record AlertDelivery(String sourceId, String deliveryId, String alertKey, AlertEventState state,
                            String payloadFingerprint, long generation, String sessionId, String incidentId,
                            String taskId, long acceptedAtMillis) {
    public AlertDelivery {
        sourceId = required(sourceId, "sourceId", 128);
        deliveryId = required(deliveryId, "deliveryId", 128);
        alertKey = required(alertKey, "alertKey", 256);
        if (state == null) { throw new IllegalArgumentException("state is required"); }
        payloadFingerprint = required(payloadFingerprint, "payloadFingerprint", 64);
        if (!payloadFingerprint.matches("[0-9a-f]{64}") || generation < 0 || acceptedAtMillis <= 0) {
            throw new IllegalArgumentException("alert delivery receipt is invalid");
        }
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value.trim();
    }
}
