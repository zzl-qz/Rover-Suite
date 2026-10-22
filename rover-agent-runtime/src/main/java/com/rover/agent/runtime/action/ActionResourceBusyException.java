package com.rover.agent.runtime.action;

/** Signals a temporary per-resource execution lock; durable workers should retry without settling the action. */
public final class ActionResourceBusyException extends RuntimeException {
    public ActionResourceBusyException(String message) { super(message); }
}
