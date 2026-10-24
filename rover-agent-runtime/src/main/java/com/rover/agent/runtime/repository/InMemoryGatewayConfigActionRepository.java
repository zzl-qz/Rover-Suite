package com.rover.agent.runtime.repository;

import com.rover.agent.core.model.ConfigActionStatus;
import com.rover.agent.core.model.GatewayConfigAction;
import com.rover.agent.core.repository.GatewayConfigActionRepository;
import com.rover.agent.core.repository.ActionUpdateConflictException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/** Synchronized bounded repository for explicit development/test in-memory mode. */
public final class InMemoryGatewayConfigActionRepository implements GatewayConfigActionRepository {
    private final int capacity;
    private final Map<String, GatewayConfigAction> actions = new ConcurrentHashMap<>();
    public InMemoryGatewayConfigActionRepository(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }
    @Override public synchronized GatewayConfigAction save(GatewayConfigAction action) {
        GatewayConfigAction current = actions.get(action.actionId());
        if (current == null ? action.version() != 0 : current.version() != action.version())
            throw new ActionUpdateConflictException(action.actionId());
        if (current != null) action.requireSameIntent(current);
        else if (actions.size() >= capacity) throw new IllegalStateException("配置动作记录已满");
        GatewayConfigAction saved = action.withVersion(action.version() + 1);
        actions.put(saved.actionId(), saved);
        return saved;
    }
    @Override public synchronized Optional<GatewayConfigAction> find(String id) { return Optional.ofNullable(actions.get(id)); }
    @Override public synchronized Optional<GatewayConfigAction> findCompensation(String id) {
        return actions.values().stream().filter(a -> id.equals(a.compensatesActionId()))
                .max(Comparator.comparingLong(GatewayConfigAction::createdAtMillis));
    }
    @Override public synchronized List<GatewayConfigAction> bySession(String sessionId) {
        return actions.values().stream().filter(a -> a.sessionId().equals(sessionId))
                .sorted(Comparator.comparingLong(GatewayConfigAction::createdAtMillis).reversed()).toList();
    }
    @Override public synchronized boolean hasNonTerminalForSession(String sessionId) {
        return actions.values().stream().anyMatch(a -> a.sessionId().equals(sessionId)
                && a.status() != ConfigActionStatus.APPLIED && a.status() != ConfigActionStatus.PRECONDITION_FAILED
                && a.status() != ConfigActionStatus.FAILED && a.status() != ConfigActionStatus.REJECTED
                && a.status() != ConfigActionStatus.EXPIRED);
    }
    @Override public synchronized List<GatewayConfigAction> byStatus(java.util.Set<ConfigActionStatus> statuses, int limit) {
        if (statuses == null || statuses.isEmpty() || limit < 1 || limit > 1000)
            throw new IllegalArgumentException("config action status query is invalid");
        return actions.values().stream().filter(a -> statuses.contains(a.status()))
                .sorted(Comparator.comparingLong(GatewayConfigAction::updatedAtMillis).reversed()).limit(limit).toList();
    }
    @Override public synchronized Optional<GatewayConfigAction> transition(String id, ConfigActionStatus expected,
            UnaryOperator<GatewayConfigAction> mutation) {
        GatewayConfigAction current = actions.get(id);
        if (current == null || current.status() != expected) return Optional.empty();
        GatewayConfigAction next = mutation.apply(current);
        if (!id.equals(next.actionId())) throw new IllegalArgumentException("配置动作 ID 不可更改");
        next.requireSameIntent(current);
        return Optional.of(save(next.withVersion(current.version())));
    }
}
