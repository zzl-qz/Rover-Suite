package com.rover.agent.runtime.task;

import com.rover.agent.core.model.Incident;
import com.rover.agent.core.model.IncidentOrigin;
import com.rover.agent.core.model.Session;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话与事件登记：把「一次提问」固定成「一个会话下的一个事件」，事件再承载调查任务。
 *
 * 本轮只做到「一次主动提问开启一个会话」；同一会话内追加调查任务（连续追问）
 * 与持久化恢复随有状态调查能力一起实现，这里先用内存实现同一个契约。
 * 会话与事件数量有上限，超出后按插入顺序淘汰最早的记录。
 */
public final class IncidentRegistry {

    private static final int MAX_ENTRIES = 100;

    private final Map<String, Session> sessions = boundedMap();
    private final Map<String, Incident> incidents = boundedMap();

    /** 开启一个新会话，用于承载本次提问所产生的调查。 */
    public Session openSession() {
        long now = System.currentTimeMillis();
        Session session = new Session(UUID.randomUUID().toString(), now, now, List.of());
        sessions.put(session.sessionId(), session);
        return session;
    }

    /** 在会话下开启一个事件；同一事件后续可以承载多次调查。 */
    public Incident openIncident(String sessionId, IncidentOrigin origin, String targetPath) {
        Incident incident = new Incident(UUID.randomUUID().toString(), sessionId, origin, targetPath,
                System.currentTimeMillis(), List.of());
        incidents.put(incident.incidentId(), incident);
        sessions.computeIfPresent(sessionId, (key, session) -> new Session(session.sessionId(),
                session.createdAtMillis(), System.currentTimeMillis(), append(session.incidentIds(), incident.incidentId())));
        return incident;
    }

    /** 把调查任务挂到事件上，使事件成为「一个问题下的多次调查」的聚合点。 */
    public void attachTask(String incidentId, String taskId) {
        incidents.computeIfPresent(incidentId, (key, incident) ->
                new Incident(incident.incidentId(), incident.sessionId(), incident.origin(), incident.targetPath(),
                        incident.createdAtMillis(), append(incident.taskIds(), taskId)));
    }

    public Optional<Incident> incident(String incidentId) {
        return Optional.ofNullable(incidents.get(incidentId));
    }

    /**
     * 撤销一个事件登记，并从所属会话的事件列表中摘除。
     *
     * 用于提交调查失败时的回滚：容量不足被拒绝的提问不应留下孤立事件。
     */
    public void removeIncident(String incidentId) {
        Incident removed = incidents.remove(incidentId);
        if (removed == null) {
            return;
        }
        sessions.computeIfPresent(removed.sessionId(), (key, session) -> new Session(session.sessionId(),
                session.createdAtMillis(), session.lastActiveAtMillis(), without(session.incidentIds(), incidentId)));
    }

    /** 撤销一个会话登记，用于回滚未成功提交的调查。 */
    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
    }

    public Optional<Session> session(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    private static List<String> append(List<String> values, String item) {
        List<String> copy = new ArrayList<>(values);
        copy.add(item);
        return List.copyOf(copy);
    }

    private static List<String> without(List<String> values, String item) {
        return values.stream().filter(value -> !value.equals(item)).toList();
    }

    private static <T> Map<String, T> boundedMap() {
        return Collections.synchronizedMap(new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, T> eldest) {
                return size() > MAX_ENTRIES;
            }
        });
    }
}