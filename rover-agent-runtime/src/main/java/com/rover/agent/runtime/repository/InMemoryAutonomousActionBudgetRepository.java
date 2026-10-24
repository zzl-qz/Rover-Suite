package com.rover.agent.runtime.repository;

import com.rover.agent.core.repository.AutonomousActionBudgetRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Synchronized test/memory implementation; production autonomous writes require durable H2. */
public final class InMemoryAutonomousActionBudgetRepository implements AutonomousActionBudgetRepository {
    private final Map<String, Budget> budgets = new HashMap<>();

    @Override public synchronized Reservation reserve(String key, String actionId, String reservationId, double impact,
            double maximum, long window, long cooldown, int direction, boolean budgeted, long now) {
        Budget budget = budgets.computeIfAbsent(key, ignored -> new Budget());
        if (actionId.equals(budget.lockedAction) && reservationId.equals(budget.reservationId)) { return Reservation.ALREADY_RESERVED; }
        if (budget.lockedAction != null) { return Reservation.RESOURCE_BUSY; }
        if (budgeted && now < budget.cooldownUntil) { return Reservation.COOLDOWN_ACTIVE; }
        if (budgeted) {
            long cutoff = now - window;
            budget.events.removeIf(event -> event.appliedAt() <= cutoff);
            double used = budget.events.stream().mapToDouble(BudgetEvent::impactPercent).sum();
            List<BudgetEvent> recent = budget.events.stream()
                    .sorted(Comparator.comparingLong(BudgetEvent::appliedAt).reversed())
                    .limit(2).toList();
            if (recent.size() >= 2 && recent.get(0).direction() != direction
                    && recent.get(1).direction() == direction) {
                return Reservation.OSCILLATION_DETECTED;
            }
            if (used + impact > maximum + 1e-9) { return Reservation.BUDGET_EXCEEDED; }
        }
        budget.lockedAction = actionId;
        budget.reservationId = reservationId;
        budget.reservedPercent = impact;
        budget.reservedCooldownMillis = cooldown;
        budget.reservedDirection = direction;
        budget.reservedBudgeted = budgeted;
        budget.reservationIds.put(actionId, reservationId);
        return Reservation.RESERVED;
    }

    @Override public synchronized void complete(String actionId, Outcome outcome, int direction, long now) {
        Budget budget = budgets.values().stream().filter(value -> actionId.equals(value.reservationId)).findFirst().orElse(null);
        if (budget == null || outcome == Outcome.UNKNOWN) { return; }
        if (outcome == Outcome.APPLIED && budget.reservedBudgeted) {
            if (direction != budget.reservedDirection) {
                throw new IllegalStateException("autonomous budget direction binding changed");
            }
            String reservationId = budget.reservationIds.getOrDefault(actionId, actionId);
            budget.events.removeIf(event -> event.reservationId().equals(reservationId));
            budget.events.add(new BudgetEvent(budget.lockedAction, reservationId, now, budget.reservedPercent, direction));
            budget.cooldownUntil = Math.addExact(now, budget.reservedCooldownMillis);
        }
        budget.lockedAction = null;
        budget.reservationId = null;
        budget.reservedPercent = 0;
        budget.reservedCooldownMillis = 0;
        budget.reservedDirection = 0;
        budget.reservedBudgeted = false;
    }

    private static final class Budget {
        String lockedAction;
        String reservationId;
        double reservedPercent;
        long reservedCooldownMillis;
        int reservedDirection;
        boolean reservedBudgeted;
        long cooldownUntil;
        final Map<String, String> reservationIds = new HashMap<>();
        final List<BudgetEvent> events = new ArrayList<>();
    }

    private record BudgetEvent(String actionId, String reservationId, long appliedAt, double impactPercent, int direction) { }
}
