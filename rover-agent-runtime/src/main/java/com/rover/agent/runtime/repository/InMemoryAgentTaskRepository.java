package com.rover.agent.runtime.repository;

import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.repository.AgentTaskRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 调查任务快照的内存实现：单机、进程内、重启即失。
 *
 * 容量满时拒绝写入（{@link StoreCapacityExceededException}）：任务记录是调查事实的真相来源，
 * 静默淘汰最早的任务会让"结论仍在事件摘要里、任务却查不到"。腾出位置由
 * {@code InvestigationTaskRegistry} 淘汰最早的已结束任务完成。
 */
public final class InMemoryAgentTaskRepository implements AgentTaskRepository {

    private final BoundedStore<String, TaskView> tasks;
    private final int capacity;

    public InMemoryAgentTaskRepository(int capacity) {
        this.tasks = new BoundedStore<>(capacity);
        this.capacity = capacity;
    }

    @Override
    public void save(TaskView task) {
        if (!tasks.put(task.taskId(), task)) {
            throw new StoreCapacityExceededException("任务存储已满（容量 " + capacity + "），无法登记新任务");
        }
    }

    @Override
    public Optional<TaskView> find(String taskId) {
        return tasks.get(taskId);
    }

    @Override
    public Optional<TaskView> findActiveBySessionId(String sessionId) {
        return tasks.valuesMatching(view -> sessionId.equals(view.sessionId()) && view.status().active())
                .stream().findFirst();
    }

    @Override
    public List<TaskView> listAll() {
        return tasks.values();
    }

    @Override
    public List<TaskView> findBySessionId(String sessionId) {
        return tasks.valuesMatching(view -> Objects.equals(view.sessionId(), sessionId));
    }

    @Override
    public List<TaskView> findByIncidentId(String incidentId) {
        return tasks.valuesMatching(view -> Objects.equals(view.incidentId(), incidentId));
    }

    @Override
    public List<TaskView> recentBySession(String sessionId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return tasks.valuesMatching(view -> sessionId.equals(view.sessionId())).stream()
                .sorted(Comparator.comparingLong(TaskView::createdAtMillis).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public void remove(String taskId) {
        tasks.remove(taskId);
    }

    @Override
    public int removeBySession(String sessionId) {
        return tasks.removeMatching(view -> Objects.equals(view.sessionId(), sessionId));
    }

    @Override
    public int removeByIncident(String incidentId) {
        return tasks.removeMatching(view -> Objects.equals(view.incidentId(), incidentId));
    }
}