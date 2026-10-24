package com.rover.agent.runtime.repository;

import com.rover.agent.core.model.ActionEffectVerification;
import com.rover.agent.core.repository.ActionEffectVerificationRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Bounded local implementation used by unit tests and explicit in-memory mode. */
public final class InMemoryActionEffectVerificationRepository implements ActionEffectVerificationRepository {
    private final Map<String, ActionEffectVerification> records = new LinkedHashMap<>();
    private final int capacity;

    public InMemoryActionEffectVerificationRepository(int capacity) {
        if (capacity < 1) { throw new IllegalArgumentException("capacity must be positive"); }
        this.capacity = capacity;
    }

    @Override public synchronized Optional<ActionEffectVerification> find(String actionId, ActionEffectVerification.Phase phase) {
        return Optional.ofNullable(records.get(key(actionId, phase)));
    }

    @Override public synchronized ActionEffectVerification save(ActionEffectVerification verification) {
        String key = key(verification.actionId(), verification.phase());
        ActionEffectVerification existing = records.get(key);
        if (existing == null && records.size() >= capacity) { throw new IllegalStateException("verification store is full"); }
        if (existing != null && existing.version() != verification.version()) {
            throw new IllegalStateException("verification version is stale");
        }
        ActionEffectVerification saved = verification.withVersion(verification.version() + 1);
        records.put(key, saved);
        return saved;
    }

    @Override public synchronized List<ActionEffectVerification> due(long nowMillis, int limit) {
        if (limit < 1 || limit > 1000) { throw new IllegalArgumentException("limit is invalid"); }
        return records.values().stream().filter(item -> item.state() == ActionEffectVerification.State.PREPARED
                        || item.state() == ActionEffectVerification.State.OBSERVING && item.nextWindowAtMillis() <= nowMillis)
                .sorted(Comparator.comparingLong(item -> item.state() == ActionEffectVerification.State.PREPARED
                        ? item.baselineAtMillis() : item.nextWindowAtMillis()))
                .limit(limit).toList();
    }

    private static String key(String actionId, ActionEffectVerification.Phase phase) {
        return actionId + "|" + phase.name();
    }
}
