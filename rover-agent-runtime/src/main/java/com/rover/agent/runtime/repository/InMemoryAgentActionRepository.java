package com.rover.agent.runtime.repository;

import com.rover.agent.core.model.ActionStatus;
import com.rover.agent.core.model.AgentAction;
import com.rover.agent.core.model.RemediationPlan;
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
    private final BoundedStore<String, RemediationPlan> plans;
    private final int capacity;

    public InMemoryAgentActionRepository(int capacity) {
        this.actions = new BoundedStore<>(capacity);
        this.plans = new BoundedStore<>(capacity);
        this.capacity = capacity;
    }

    @Override
    public synchronized AgentAction save(AgentAction action) {
        AgentAction current = actions.get(action.actionId()).orElse(null);
        if (current == null ? action.version() != 0 : current.version() != action.version()
                || action.version() == 0) {
            throw new ActionUpdateConflictException(action.actionId());
        }
        AgentAction parent = null;
        if (current != null) {
            action.requireSameIntent(current);
            if (current.status() == ActionStatus.SUPERSEDED && action.status() != current.status()) {
                throw new ActionUpdateConflictException(action.actionId());
            }
            if (action.plan() != null && action.status() == ActionStatus.EXECUTING
                    && latest(action.plan().planId()).planVersion() != action.plan().planVersion()) {
                throw new ActionUpdateConflictException(action.actionId());
            }
        } else {
            if (plans.get(action.actionId()).isPresent()) {
                throw new ActionUpdateConflictException(action.actionId());
            }
            parent = validateNewPlan(action);
            if (actions.size() >= capacity || action.plan() != null && plans.size() >= capacity) {
                throw new StoreCapacityExceededException("变更记录或计划历史已满（容量 " + capacity + "），无法登记新变更");
            }
        }
        AgentAction saved = action.withVersion(Math.addExact(action.version(), 1L));
        // 所有校验和容量检查先完成，同一锁内提交动作、计划与旧版本状态。
        AgentAction superseded = parent == null ? null
                : parent.superseded(action.createdAtMillis()).withVersion(Math.addExact(parent.version(), 1L));
        if (!actions.put(action.actionId(), saved)) {
            throw new StoreCapacityExceededException("变更记录已满（容量 " + capacity + "），无法登记新变更");
        }
        if (current == null && action.plan() != null) { plans.put(action.actionId(), action.plan()); }
        if (superseded != null) { actions.put(superseded.actionId(), superseded); }
        return saved;
    }

    private AgentAction validateNewPlan(AgentAction action) {
        RemediationPlan plan = action.plan();
        if (plan == null) { return null; }
        plan.requireMatches(action);
        if (action.status() != ActionStatus.PENDING_APPROVAL || action.applyOperationId() != null
                || action.rollbackOperationId() != null || action.approvedAtMillis() != 0) {
            throw new IllegalArgumentException("新计划必须从待批准状态开始");
        }
        RemediationPlan previous = latest(plan.planId());
        if (previous == null) {
            if (plan.planVersion() != 1) { throw new ActionUpdateConflictException(action.actionId()); }
            return null;
        }
        if (plan.planVersion() != previous.planVersion() + 1) {
            throw new ActionUpdateConflictException(action.actionId());
        }
        AgentAction parent = actions.valuesMatching(candidate -> candidate.plan() != null
                && candidate.plan().equals(previous)).stream().findFirst().orElse(null);
        if (parent == null || parent.status() != ActionStatus.PENDING_APPROVAL
                || !Objects.equals(parent.sessionId(), action.sessionId())
                || !Objects.equals(parent.resourceRef(), action.resourceRef())
                || !Objects.equals(parent.routeId(), action.routeId())
                || !Objects.equals(parent.businessPrefix(), action.businessPrefix())
                || !Objects.equals(parent.serviceName(), action.serviceName())
                || !Objects.equals(parent.group(), action.group()) || parent.type() != action.type()) {
            throw new ActionUpdateConflictException(action.actionId());
        }
        return parent;
    }

    private RemediationPlan latest(String planId) {
        List<RemediationPlan> versions = planVersions(planId);
        return versions.isEmpty() ? null : versions.get(versions.size() - 1);
    }

    @Override
    public synchronized List<RemediationPlan> planVersions(String planId) {
        return plans.valuesMatching(plan -> Objects.equals(plan.planId(), planId)).stream()
                .sorted(Comparator.comparingInt(RemediationPlan::planVersion)).toList();
    }

    @Override
    public synchronized Optional<AgentAction> find(String actionId) {
        return actionId == null ? Optional.empty() : actions.get(actionId);
    }

    @Override
    public synchronized List<AgentAction> bySession(String sessionId) {
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
