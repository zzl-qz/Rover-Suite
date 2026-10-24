package com.rover.agent.core.repository;

import com.rover.agent.core.model.ActionExecutionCommand;
import java.util.List;
import java.util.Optional;

/** Durable queue for approved external actions. Commands are idempotent by commandId. */
public interface ActionCommandRepository {

    void enqueue(ActionExecutionCommand command);

    List<Job> pending(long nowMillis, int limit);

    Optional<Job> claim(String commandId, long expectedAttempt);

    void finish(String commandId, long attempt);

    void retry(String commandId, long attempt, long notBeforeMillis, String reason);

    record Job(ActionExecutionCommand command, long attempt, String state, long notBeforeMillis) { }
}
