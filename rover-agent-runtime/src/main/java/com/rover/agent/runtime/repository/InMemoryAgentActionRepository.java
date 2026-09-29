package com.rover.agent.runtime.repository;

import com.rover.agent.core.model.ActionStatus;
import com.rover.agent.core.model.AgentAction;
import com.rover.agent.core.repository.AgentActionRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * 变更记录的内存实现：单机、进程内、重启即失，用于未配记录库路径的运行与单测。
 *
 * <p>{@link #transition} 的原子性靠 {@link BoundedStore} 的同一把锁保证：读当前状态、比对、
 * 写回在同一个同步块里完成，因此「双击批准」在内存实现下同样只会有一次成功。
 * 这条语义必须在两种实现里一致——否则测试通过、上生产就出两笔变更。
 */
public final class InMemoryAgentActionRepository implements AgentActionRepository {

    private final BoundedStore<String, AgentAction> actions;
    private final int capacity;

    public InMemoryAgentActionRepository(int capacity) {
        this.actions = new BoundedStore<>(capacity);
        this.capacity = capacity;
    }

    @Override
    public void save(AgentAction action) {
        if (!actions.put(action.actionId(), action)) {
            throw new StoreCapacityExceededException("变更记录已满（容量 " + capacity + "），无法登记新变更");
        }
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
        actions.put(actionId, moved);
        return Optional.of(moved);
    }

    @Override
    public int removeBySession(String sessionId) {
        return actions.removeMatching(action -> Objects.equals(action.sessionId(), sessionId));
    }
}
