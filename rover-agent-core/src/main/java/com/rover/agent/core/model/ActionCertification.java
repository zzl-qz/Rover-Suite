package com.rover.agent.core.model;

import java.util.Objects;

/** Immutable versioned operator certification for one exact shadow-evaluated subject. */
public record ActionCertification(ActionCertificationSubject subject, long version, State state, int passingRuns,
        String sampleHash, String issuedBy, long issuedAtMillis, long expiresAtMillis,
        String updatedBy, long updatedAtMillis) {
    public ActionCertification {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(state, "state");
        if (version < 1 || passingRuns < 0 || sampleHash == null || !sampleHash.matches("[0-9a-f]{64}")
                || issuedBy == null || issuedBy.isBlank() || issuedBy.length() > 128 || issuedAtMillis <= 0
                || expiresAtMillis <= issuedAtMillis || updatedBy == null || updatedBy.isBlank()
                || updatedBy.length() > 128 || updatedAtMillis < issuedAtMillis) {
            throw new IllegalArgumentException("certification record is invalid");
        }
    }
    public boolean current(ActionCertificationSubject currentSubject, long nowMillis) {
        return state == State.ACTIVE && expiresAtMillis > nowMillis && subject.fingerprint().equals(currentSubject.fingerprint());
    }
    public boolean current(ActionCertificationSubject currentSubject, long nowMillis, String sampleHash) {
        return current(currentSubject, nowMillis) && this.sampleHash.equals(sampleHash);
    }
    public ActionCertification revoke(String actor, long nowMillis) {
        return new ActionCertification(subject, Math.addExact(version, 1), State.REVOKED, passingRuns, sampleHash,
                issuedBy, issuedAtMillis, expiresAtMillis, actor, nowMillis);
    }
    public enum State { ACTIVE, REVOKED }
}
