package com.rover.agent.runtime.task;

/** A delivery or active alert key was reused for a different alert payload. */
public final class AlertDeliveryConflictException extends RuntimeException {
    public AlertDeliveryConflictException(String message) { super(message); }
}
