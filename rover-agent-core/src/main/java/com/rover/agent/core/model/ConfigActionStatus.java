package com.rover.agent.core.model;

/** Lifecycle of an approved, allowlisted runtime configuration change. */
public enum ConfigActionStatus {
    PENDING_APPROVAL, QUEUED, EXECUTING, APPLIED, PRECONDITION_FAILED, FAILED, UNKNOWN,
    RECONCILE_QUEUED, RECONCILING, REJECTED, EXPIRED
}
