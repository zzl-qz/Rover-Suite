package com.rover.agent.core.model;

import java.util.Objects;

/** Immutable no-write evaluation of a candidate plan against the current control-plane snapshot. */
public record ActionShadowRun(String runId, ActionCertificationSubject subject, String actionId, int planVersion,
        String planHash, Outcome outcome, String detail, int observedRevision, String previewHash, long observedAtMillis,
        String assessedBy) {
    public ActionShadowRun(String runId, ActionCertificationSubject subject, String actionId, int planVersion,
            String planHash, Outcome outcome, String detail, int observedRevision, String previewHash, long observedAtMillis) {
        this(runId, subject, actionId, planVersion, planHash, outcome, detail, observedRevision, previewHash,
                observedAtMillis, "system:unknown");
    }

    public ActionShadowRun {
        if (runId == null || !runId.matches("[A-Za-z0-9._-]{1,64}") || actionId == null || actionId.isBlank()
                || actionId.length() > 64 || planVersion < 1 || planHash == null || !planHash.matches("[0-9a-f]{64}")
                || observedRevision < -1 || observedAtMillis <= 0) {
            throw new IllegalArgumentException("shadow assessment identity is invalid");
        }
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(outcome, "outcome");
        if (assessedBy == null || assessedBy.isBlank() || assessedBy.length() > 128) {
            throw new IllegalArgumentException("shadow evaluator identity is required and bounded");
        }
        detail = detail == null ? "" : detail.substring(0, Math.min(detail.length(), 2000));
        previewHash = previewHash == null ? "" : previewHash;
        if (!previewHash.isEmpty() && !previewHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("preview hash must be SHA-256");
        }
    }
    public enum Outcome { PASS, FAIL }
}
