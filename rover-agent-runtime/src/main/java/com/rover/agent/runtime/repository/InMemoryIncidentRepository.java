package com.rover.agent.runtime.repository;

import com.rover.agent.core.model.Incident;
import com.rover.agent.core.repository.IncidentRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 事件内存存储；容量满时拒绝新增，由上层连同关联任务整组清理。 */
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