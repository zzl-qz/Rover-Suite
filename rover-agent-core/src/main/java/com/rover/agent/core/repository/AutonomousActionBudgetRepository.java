package com.rover.agent.core.repository;

/** Atomic per-resource lock, rolling impact budget, and cooldown for autonomous actions. */
public interface AutonomousActionBudgetRepository {
    Reservation reserve(String resourceKey, String actionId, String reservationId, double impactPercent,
                        double maximumCumulativePercent, long windowMillis, long cooldownMillis,
                        int direction, boolean budgeted, long nowMillis);

    default Reservation reserve(String resourceKey, String actionId, double impactPercent,
                                double maximumCumulativePercent, long windowMillis, long cooldownMillis,
                                int direction, boolean budgeted, long nowMillis) {
        return reserve(resourceKey, actionId, actionId, impactPercent, maximumCumulativePercent,
                windowMillis, cooldownMillis, direction, budgeted, nowMillis);
    }

    /** Applied charges the reserved impact; NOT_APPLIED releases it; UNKNOWN keeps the resource locked. */
    void complete(String actionId, Outcome outcome, int direction, long nowMillis);

    enum Reservation { RESERVED, ALREADY_RESERVED, RESOURCE_BUSY, COOLDOWN_ACTIVE, BUDGET_EXCEEDED, OSCILLATION_DETECTED }
    enum Outcome { APPLIED, NOT_APPLIED, UNKNOWN }
}
