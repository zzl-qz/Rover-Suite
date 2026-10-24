package com.rover.agent.core.model;

import java.util.List;
import java.util.Objects;

/** Scoped candidate plus the exact recent evidence needed for an operator certification decision. */
public record ActionShadowCandidate(AgentAction action, ActionCertificationSubject subject,
        List<ActionShadowRun> recentRuns, int distinctPlanVersions, boolean certificationEligible) {
    public ActionShadowCandidate {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(subject, "subject");
        recentRuns = List.copyOf(Objects.requireNonNull(recentRuns, "recentRuns"));
        if (distinctPlanVersions < 0 || distinctPlanVersions > recentRuns.size()) {
            throw new IllegalArgumentException("shadow candidate sample summary is invalid");
        }
    }
}
