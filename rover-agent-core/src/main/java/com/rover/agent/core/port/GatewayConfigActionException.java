package com.rover.agent.core.port;

/** Failure at the Gateway config action boundary, preserving whether a write outcome is uncertain. */
public final class GatewayConfigActionException extends RuntimeException {
    private final boolean outcomeUnknown;

    public GatewayConfigActionException(String message, boolean outcomeUnknown, Throwable cause) {
        super(message, cause);
        this.outcomeUnknown = outcomeUnknown;
    }

    public boolean outcomeUnknown() { return outcomeUnknown; }
}
