package com.rover.agent.core.port;

/** Typed result returned by Gateway's durable configuration action endpoint. */
public record GatewayConfigActionReceipt(String operationId, String configKey, String expectedValue,
                                         String desiredValue, Status status, boolean replayed,
                                         String message) {

    public GatewayConfigActionReceipt {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId is required");
        }
        operationId = operationId.trim();
        configKey = configKey == null || configKey.isBlank() ? null : configKey.trim();
        expectedValue = expectedValue == null || expectedValue.isBlank() ? null : expectedValue.trim();
        desiredValue = desiredValue == null || desiredValue.isBlank() ? null : desiredValue.trim();
        if (status == null) { throw new IllegalArgumentException("status is required"); }
        message = message == null ? "" : message;
    }

    public enum Status { APPLIED, PRECONDITION_FAILED, UNKNOWN, FAILED }
}
