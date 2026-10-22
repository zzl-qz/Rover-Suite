package com.rover.agent.core.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Persisted business-effect observation for an applied or compensated action. */
public record ActionEffectVerification(String actionId, Phase phase, int planVersion, String planHash,
                                       State state, Outcome outcome, long baselineAtMillis, long appliedAtMillis,
                                       long nextWindowAtMillis, long deadlineAtMillis, int windowsSeen,
                                       int consecutiveRecoveredWindows, Map<String, Double> baselineValues,
                                       Map<String, Double> latestValues, List<Window> windows,
                                       List<String> limitations, long version) {
    public ActionEffectVerification {
        actionId = required(actionId, 64);
        Objects.requireNonNull(phase, "phase");
        if (planVersion < 1 || planHash == null || !planHash.matches("[0-9a-f]{64}")
                || baselineAtMillis <= 0 || deadlineAtMillis < baselineAtMillis || windowsSeen < 0
                || consecutiveRecoveredWindows < 0 || version < 0) {
            throw new IllegalArgumentException("verification binding or counters are invalid");
        }
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(outcome, "outcome");
        baselineValues = finite(baselineValues);
        latestValues = finite(latestValues);
        windows = windows == null ? List.of() : List.copyOf(windows);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        if (windows.size() > 20 || limitations.size() > 100 || limitations.stream().anyMatch(value -> value == null
                || value.length() > 1000)) {
            throw new IllegalArgumentException("verification history exceeds its bound");
        }
        if (state == State.OBSERVING && (appliedAtMillis <= 0 || nextWindowAtMillis <= 0)) {
            throw new IllegalArgumentException("observing verification requires an applied time and next window");
        }
        if (state == State.PREPARED && (appliedAtMillis != 0 || nextWindowAtMillis != 0
                || outcome != Outcome.PENDING || windowsSeen != 0 || consecutiveRecoveredWindows != 0)) {
            throw new IllegalArgumentException("prepared verification cannot contain observation results");
        }
        if (state == State.COMPLETE && (outcome == Outcome.PENDING || outcome == Outcome.IMPROVED)) {
            throw new IllegalArgumentException("completed verification requires a final outcome");
        }
    }

    public ActionEffectVerification withVersion(long value) {
        return new ActionEffectVerification(actionId, phase, planVersion, planHash, state, outcome, baselineAtMillis,
                appliedAtMillis, nextWindowAtMillis, deadlineAtMillis, windowsSeen, consecutiveRecoveredWindows,
                baselineValues, latestValues, windows, limitations, value);
    }

    private static String required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) { throw new IllegalArgumentException("actionId is invalid"); }
        return value.trim();
    }
    private static Map<String, Double> finite(Map<String, Double> values) {
        if (values == null) { return Map.of(); }
        if (values.size() > 32 || values.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getKey().isBlank() || entry.getValue() == null || !Double.isFinite(entry.getValue()))) {
            throw new IllegalArgumentException("verification metrics are invalid");
        }
        return Map.copyOf(values);
    }

    public enum Phase { APPLY, COMPENSATION }
    public enum State { PREPARED, OBSERVING, COMPLETE }
    public enum Outcome { PENDING, RECOVERED, IMPROVED, NOT_RECOVERED, WORSENED, BASELINE_ALREADY_HEALTHY,
        INSUFFICIENT_SAMPLE, MISSING_DATA, CONTAMINATED, NOT_APPLIED }
    public record Window(long fromMillis, long toMillis, Outcome outcome, long requests, double coverage,
                         Map<String, Double> values, List<String> limitations) {
        public Window {
            if (fromMillis <= 0 || toMillis < fromMillis || requests < 0 || !Double.isFinite(coverage)
                    || coverage < 0 || coverage > 1) { throw new IllegalArgumentException("verification window is invalid"); }
            Objects.requireNonNull(outcome, "outcome");
            values = finite(values);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
            if (limitations.size() > 100 || limitations.stream().anyMatch(value -> value == null || value.length() > 1000)) {
                throw new IllegalArgumentException("verification window limitations exceed their bound");
            }
        }
    }
}
