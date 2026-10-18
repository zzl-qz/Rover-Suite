package com.rover.agent.runtime.repository;

import com.rover.agent.core.model.ActionStatus;
import com.rover.agent.core.model.AgentAction;
import com.rover.agent.core.repository.AgentActionRepository;
import com.rover.agent.core.repository.ActionUpdateConflictException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** 变更记录的内存存储；transition 在同一同步块内比较状态并写入。 */
public final class InMemoryAgentActionRepository implements AgentActionRepository {

    private final BoundedStore<String, AgentAction> actions;
    private final int capacity;

    public InMemoryAgentActionRepository(int capacity) {
        this.actions = new BoundedStore<>(capacity);
        this.capacity = capacity;
    }

    @Override
    public synchronized AgentAction save(AgentAction action) {
        AgentAction current = actions.get(action.actionId()).orElse(null);
        if (current == null ? action.version() != 0 : current.version() != action.version()
                || action.version() == 0) {
            throw new ActionUpdateConflictException(action.actionId());
        }
        AgentAction saved = action.withVersion(Math.addExact(action.version(), 1L));
        if (!actions.put(action.actionId(), saved)) {
            throw new StoreCapacityExceededException("变更记录已满（容量 " + capacity + "），无法登记新变更");
        }
        return saved;
    }

    @Override
    public Optional<AgentAction> find(String actionId) {
        return actionId == null ? Optional.empty() : actions.get(actionId);
    }

    @Override
    public List<AgentAction> bySession(String sessionId) {
        return actions.valuesMatching(action -> Objects.equals(action.sessionId(), sessionId)).stream()
                .sorted(Comparator.comparingLong(AgentAction::createdAtMillis).reversed())
                .toList();
    }

    @Override
    public synchronized Optional<AgentAction> transition(String actionId, ActionStatus expected,
                                                         UnaryOperator<AgentAction> mutation) {
        AgentAction current = actions.get(actionId).orElse(null);
        if (current == null || current.status() != expected) {
            return Optional.empty();
        }
        AgentAction moved = mutation.apply(current);
        if (!actionId.equals(moved.actionId())) {
            throw new IllegalArgumentException("状态迁移不能改变变更 ID");
        }
        return Optional.of(save(moved.withVersion(current.version())));
    }

    @Override
    public synchronized int removeBySession(String sessionId) {
        return actions.removeMatching(action -> Objects.equals(action.sessionId(), sessionId));
    }
}
