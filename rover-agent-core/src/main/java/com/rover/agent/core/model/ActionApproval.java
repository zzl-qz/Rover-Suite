package com.rover.agent.core.model;

import java.util.Objects;

/** Immutable approval receipt bound to one action, plan version and active policy. */
public record ActionApproval(String actionId, Stage stage, int planVersion, String planHash,
                             String policyVersion, String approver, long approvedAtMillis) {

    public static final int LEGACY_PLAN_VERSION = 0;
    public static final String LEGACY_PLAN_HASH = "0".repeat(64);

    public ActionApproval {
        actionId = required(actionId, "actionId", 64);
        Objects.requireNonNull(stage, "stage");
        if (planVersion < LEGACY_PLAN_VERSION) { throw new IllegalArgumentException("planVersion is invalid"); }
        planHash = required(planHash, "planHash", 64);
        if (planVersion == LEGACY_PLAN_VERSION ? !LEGACY_PLAN_HASH.equals(planHash)
                : !planHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("plan binding is invalid");
        }
        policyVersion = required(policyVersion, "policyVersion", 128);
        approver = required(approver, "approver", 128);
        if (approvedAtMillis <= 0) { throw new IllegalArgumentException("approval time is invalid"); }
    }

    private static String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value.trim();
    }

    public enum Stage { APPLY, ROLLBACK }
}
