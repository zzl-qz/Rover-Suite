package com.rover.agent.runtime.repository;

import com.rover.agent.core.model.ActionExecutionCommand;
import com.rover.agent.core.repository.ActionCommandRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Bounded local command queue used by tests and explicit in-memory mode. */
public final class InMemoryActionCommandRepository implements ActionCommandRepository {

    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final int capacity;

    public InMemoryActionCommandRepository(int capacity) {
        if (capacity < 1) { throw new IllegalArgumentException("capacity must be positive"); }
        this.capacity = capacity;
    }

    @Override
    public synchronized void enqueue(ActionExecutionCommand command) {
        Job existing = jobs.get(command.commandId());
        if (existing != null) {
            if ("DONE".equals(existing.state())) {
                jobs.put(command.commandId(), new Job(command, existing.attempt(), "PENDING", 0));
            }
            return;
        }
        if (jobs.size() >= capacity) { throw new IllegalStateException("action command queue is full"); }
        jobs.put(command.commandId(), new Job(command, 0, "PENDING", 0));
    }

    @Override
    public synchronized List<Job> pending(long nowMillis, int limit) {
        if (limit < 1) { throw new IllegalArgumentException("limit must be positive"); }
        return jobs.values().stream().filter(job -> "PENDING".equals(job.state())
                        && job.notBeforeMillis() <= nowMillis)
                .sorted(Comparator.comparingLong(job -> job.command().createdAtMillis()))
                .limit(limit).toList();
    }

    @Override
    public synchronized Optional<Job> claim(String commandId, long expectedAttempt) {
        Job current = jobs.get(commandId);
        if (current == null || !"PENDING".equals(current.state()) || current.attempt() != expectedAttempt) {
            return Optional.empty();
        }
        Job claimed = new Job(current.command(), current.attempt() + 1, "RUNNING", current.notBeforeMillis());
        jobs.put(commandId, claimed);
        return Optional.of(claimed);
    }

    @Override
    public synchronized void finish(String commandId, long attempt) {
        Job current = requireRunning(commandId, attempt);
        jobs.put(commandId, new Job(current.command(), attempt, "DONE", current.notBeforeMillis()));
    }

    @Override
    public synchronized void retry(String commandId, long attempt, long notBeforeMillis, String reason) {
        Job current = requireRunning(commandId, attempt);
        jobs.put(commandId, new Job(current.command(), attempt, "PENDING", notBeforeMillis));
    }

    private Job requireRunning(String commandId, long attempt) {
        Job current = jobs.get(commandId);
        if (current == null || !"RUNNING".equals(current.state()) || current.attempt() != attempt) {
            throw new IllegalStateException("action command attempt is stale");
        }
        return current;
    }
}
