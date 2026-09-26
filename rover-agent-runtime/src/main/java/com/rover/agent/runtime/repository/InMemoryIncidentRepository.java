package com.rover.agent.runtime.repository;

import com.rover.agent.core.model.Incident;
import com.rover.agent.core.repository.IncidentRepository;
import java.util.List;
import java.util.Optional;

/** 事件的内存实现：单机、进程内、重启即失。 */
public final class InMemoryIncidentRepository implements IncidentRepository {

    private final BoundedStore<String, Incident> incidents;

    public InMemoryIncidentRepository(int capacity) {
        this.incidents = new BoundedStore<>(capacity);
    }

    @Override
    public void save(Incident incident) {
        incidents.put(incident.incidentId(), incident);
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
        return incidents.valuesMatching(incident -> incident.sessionId().equals(sessionId));
    }

    @Override
    public void remove(String incidentId) {
        incidents.remove(incidentId);
    }
}