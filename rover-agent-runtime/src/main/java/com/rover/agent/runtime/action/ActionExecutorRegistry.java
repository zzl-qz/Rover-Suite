package com.rover.agent.runtime.action;

import com.rover.agent.core.model.ActionType;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/** Resolves a typed action to exactly one deterministic executor. */
public final class ActionExecutorRegistry {

    private final Map<ActionType, ActionExecutor> executors;

    public ActionExecutorRegistry(Collection<ActionExecutor> executors) {
        EnumMap<ActionType, ActionExecutor> registered = new EnumMap<>(ActionType.class);
        for (ActionExecutor executor : executors) {
            ActionExecutor previous = registered.putIfAbsent(executor.type(), executor);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate action executor: " + executor.type());
            }
        }
        this.executors = Map.copyOf(registered);
    }

    public ActionExecutor require(ActionType type) {
        ActionExecutor executor = executors.get(type);
        if (executor == null) { throw new IllegalArgumentException("no executor registered for " + type); }
        return executor;
    }
}
