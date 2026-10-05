package com.rover.agent.runtime.repository;

import com.rover.agent.core.model.AgentCheckpoint;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.repository.AgentCheckpointRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 安全恢复点的内存实现，按调用方快照原子保存，重启后丢失。 */
public final class InMemoryAgentCheckpointRepository implements AgentCheckpointRepository {

    private final Map<String, List<AgentCheckpoint>> marksByTask = new ConcurrentHashMap<>();

    @Override
    public void save(TaskView task, AgentCheckpoint checkpoint) {
        if (task == null || checkpoint == null) {
            throw new IllegalArgumentException("恢复点必须挂在任务上");
        }
        marksByTask.computeIfAbsent(task.taskId(), key -> new ArrayList<>()).add(checkpoint);
    }

    @Override
    public Optional<AgentCheckpoint> latest(String taskId) {
        List<AgentCheckpoint> marks = marksByTask.get(taskId);
        if (marks == null || marks.isEmpty()) {
            return Optional.empty();
        }
        return marks.stream().max(Comparator.comparingInt(AgentCheckpoint::sequenceNo));
    }

    @Override
    public List<AgentCheckpoint> byTask(String taskId) {
        List<AgentCheckpoint> marks = marksByTask.get(taskId);
        return marks == null ? List.of() : List.copyOf(marks);
    }

    @Override
    public int removeByTask(String taskId) {
        List<AgentCheckpoint> removed = marksByTask.remove(taskId);
        return removed == null ? 0 : removed.size();
    }
}
