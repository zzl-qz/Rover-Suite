package com.rover.agent.runtime.repository;

import com.rover.agent.core.model.Incident;
import com.rover.agent.core.repository.IncidentRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 事件的内存实现：单机、进程内、重启即失。
 *
 * 容量满时拒绝写入（{@link StoreCapacityExceededException}），不淘汰已有事件：
 * 事件是任务的聚合点，被丢掉的会议让"任务挂在哪个事件上"出现悬空引用。
 * 腾出位置由上层保留策略整组清理（最早的事件连同它的任务）。
 */
public final class InMemoryIncidentRepository implements IncidentRepository {

    private final BoundedStore<String, Incident> incidents;
    private final int capacity;

    public InMemoryIncidentRepository(int capacity) {
        this.incidents = new BoundedStore<>(capacity);
        this.capacity = capacity;
    }

    @Override
    public void save(Incident incident) {
        if (!incidents.put(incident.incidentId(), incident)) {
            throw new StoreCapacityExceededException("事件存储已满（容量 " + capacity + "），无法登记新事件");
        }
    }

    @Override
    public Optional<Incident> find(String incidentId) {
        return incidents.get(incidentId);
    }

    @Override
    public List<Incident> listAll() {
        return incidents.values();
    }

    @Override
    public List<Incident> bySession(String sessionId) {
        return incidents.valuesMatching(incident -> Objects.equals(incident.sessionId(), sessionId));
    }

    @Override
    public void remove(String incidentId) {
        incidents.remove(incidentId);
    }

    @Override
    public int removeBySession(String sessionId) {
        return incidents.removeMatching(incident -> Objects.equals(incident.sessionId(), sessionId));
    }
}