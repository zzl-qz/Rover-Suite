package com.rover.agent.core.planning;

import com.rover.agent.core.capability.AgentCapability;
import java.util.List;

/** 一次已完成的读取；没有拿到证据也是一次尝试，重启后不能清零。 */
public record CapabilityAttempt(AgentCapability capability, Outcome outcome, List<String> evidenceIds,
                                String fingerprint, long completedAtMillis) {
    public enum Outcome { COLLECTED, RETRYABLE_FAILURE, UNAVAILABLE, SKIPPED }
    public CapabilityAttempt {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
}
