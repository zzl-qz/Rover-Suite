package com.rover.agent.runtime.repository;

import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.repository.AgentTaskRepository;
import java.util.List;
import java.util.Optional;

/** 调查任务快照的内存实现：单机、进程内、重启即失。 */
public final class InMemoryAgentTaskRepository implements AgentTaskRepository {

    private final BoundedStore<String, TaskView> tasks;

    public InMemoryAgentTaskRepository(int capacity) {
        this.tasks = new BoundedStore<>(capacity);
    }

    @Override
    public void save(TaskView task) {
        tasks.put(task.taskId(), task);
    }

    @Override
    public Optional<TaskView> find(String taskId) {
        return tasks.get(taskId);
    }

    @Override
    public List<TaskView> listAll() {
        return tasks.values();
    }

    @Override
    public void remove(String taskId) {
        tasks.remove(taskId);
    }
}