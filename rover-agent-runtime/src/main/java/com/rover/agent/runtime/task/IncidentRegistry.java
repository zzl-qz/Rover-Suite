package com.rover.agent.runtime.task;

import com.rover.agent.core.model.Incident;
import com.rover.agent.core.model.IncidentOrigin;
import com.rover.agent.core.model.IncidentSeverity;
import com.rover.agent.core.model.IncidentStatus;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.SessionStatus;
import com.rover.agent.core.model.TimeRange;
import com.rover.agent.core.repository.AgentSessionRepository;
import com.rover.agent.core.repository.IncidentRepository;
import com.rover.agent.runtime.repository.InMemoryAgentSessionRepository;
import com.rover.agent.runtime.repository.InMemoryIncidentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话与事件登记：把「一次提问」固定成「一个会话下的一个事件」，事件再承载调查任务。
 *
 * 记录存放在 {@link AgentSessionRepository} 与 {@link IncidentRepository}，本类不直接持有任何集合，
 * 因此后续换成持久化实现时业务代码不需要改。无参构造使用内存实现（单机、重启即失）；
 * 由组合根注入具体实现时走两参构造。
 *
 * 会话与事件数量有上限：启用保留策略（{@link WorkspaceRetention}）时，容量满会整组清理最早的会话
 * 或最早的事件，不留孤儿记录；未启用时（如测试里的小容量场景）容量满会直接拒绝写入并报错。
 */
public final class IncidentRegistry {

    private static final int MAX_ENTRIES = 100;

    private final AgentSessionRepository sessions;
    private final IncidentRepository incidents;
    private final WorkspaceRetention retention;

    /** 当前阶段的内存实现；接入持久化后由组合根注入具体实现。 */
    public IncidentRegistry() {
        this(new InMemoryAgentSessionRepository(MAX_ENTRIES), new InMemoryIncidentRepository(MAX_ENTRIES));
    }

    public IncidentRegistry(AgentSessionRepository sessions, IncidentRepository incidents) {
        this(sessions, incidents, null);
    }

    public IncidentRegistry(AgentSessionRepository sessions, IncidentRepository incidents,
                            WorkspaceRetention retention) {
        this.sessions = sessions;
        this.incidents = incidents;
        this.retention = retention;
    }

    /** 开启一个没有归属用户的会话；仅供尚未接入认证上下文的调用方使用。 */
    public Session openSession() {
        return openSession(null);
    }

    /** 开启一个归属指定用户的会话；{@code userId} 必须来自后端认证上下文。 */
    public Session openSession(String userId) {
        long now = System.currentTimeMillis();
        Session session = new Session(UUID.randomUUID().toString(), userId, null, null, SessionStatus.ACTIVE,
                now, now, List.of());
        admit(session);
        return session;
    }

    /** 在会话下开启一个事件；新事件即成为该会话的当前事件，后续追问默认落到它上面。 */
    public Incident openIncident(String sessionId, IncidentOrigin origin, ResourceTarget target) {
        return openIncident(sessionId, origin, target, TimeRange.unspecified());
    }

    /** 在会话下开启一个带指定时间范围的事件；其余语义同 {@link #openIncident(String, IncidentOrigin, ResourceTarget)}。 */
    public Incident openIncident(String sessionId, IncidentOrigin origin, ResourceTarget target, TimeRange timeRange) {
        long now = System.currentTimeMillis();
        Incident incident = new Incident(UUID.randomUUID().toString(), sessionId, origin, target.value(),
                IncidentStatus.OPEN, IncidentSeverity.UNKNOWN, target,
                timeRange == null ? TimeRange.unspecified() : timeRange, "",
                now, now, List.of());
        if (retention == null) {
            incidents.save(incident);
        } else {
            retention.admitIncident(incident);
        }
        sessions.find(sessionId).ifPresent(session -> sessions.save(SessionLinks.withIncident(session, incident.incidentId())));
        return incident;
    }

    /** 更新事件的调查时间范围（用户手工指定时），返回更新后的事件；事件不存在时返回 {@code null}。 */
    public Incident updateTimeRange(String incidentId, TimeRange timeRange) {
        Incident incident = incidents.find(incidentId).orElse(null);
        if (incident == null) {
            return null;
        }
        Incident updated = new Incident(incident.incidentId(), incident.sessionId(), incident.origin(),
                incident.title(), incident.status(), incident.severity(), incident.target(),
                timeRange == null ? TimeRange.unspecified() : timeRange, incident.summary(),
                incident.createdAtMillis(), System.currentTimeMillis(), incident.taskIds());
        incidents.save(updated);
        return updated;
    }

    /** 把调查任务挂到事件上：事件成为「一个问题下的多次调查」的聚合点，并进入调查中。 */
    public void attachTask(String incidentId, String taskId) {
        incidents.find(incidentId).ifPresent(incident -> incidents.save(new Incident(
                incident.incidentId(), incident.sessionId(), incident.origin(), incident.title(),
                IncidentStatus.INVESTIGATING, incident.severity(), incident.target(), incident.timeRange(),
                incident.summary(), incident.createdAtMillis(), System.currentTimeMillis(),
                SessionLinks.append(incident.taskIds(), taskId))));
    }

    /**
     * 回写调查结论：事件摘要更新为最近一次结论，状态转为已结论。
     *
     * 同一事件上的后续调查会由 {@link #attachTask} 重新置为调查中，因此摘要始终是「最近一轮结论」，
     * 而状态始终表达「当前是否还有在跑的调查」。
     */
    public void summarise(String incidentId, String summary) {
        incidents.find(incidentId).ifPresent(incident -> incidents.save(new Incident(
                incident.incidentId(), incident.sessionId(), incident.origin(), incident.title(),
                IncidentStatus.RESOLVED, incident.severity(), incident.target(), incident.timeRange(),
                summary == null ? "" : summary, incident.createdAtMillis(), System.currentTimeMillis(),
                incident.taskIds())));
    }

    public Optional<Incident> incident(String incidentId) {
        return incidents.find(incidentId);
    }

    /**
     * 撤销一个事件登记，并从所属会话的事件列表与当前事件指针中摘除。
     *
     * 用于提交调查失败时的回滚：容量不足被拒绝的提问不应留下孤立事件。
     */
    public void removeIncident(String incidentId) {
        Optional<Incident> removed = incidents.find(incidentId);
        incidents.remove(incidentId);
        removed.ifPresent(incident -> sessions.find(incident.sessionId())
                .ifPresent(session -> sessions.save(SessionLinks.withoutIncident(session, incidentId))));
    }

    /**
     * 撤销一个会话登记，用于回滚未成功提交的调查。
     *
     * 启用保留策略时整组清理：会话、它的事件、消息与任务一起删，避免"会话回滚了、
     * 它的任务记录还挂在事件上"这种孤儿（旧入口在队列满时正是这条路径）。
     */
    public void removeSession(String sessionId) {
        if (retention == null) {
            sessions.remove(sessionId);
            return;
        }
        retention.deleteSessionCascade(sessionId);
    }

    public Optional<Session> session(String sessionId) {
        return sessions.find(sessionId);
    }

    /** 登记会话：有保留策略时按容量准入（必要时整组清理最早的会话），否则直接写入。 */
    private void admit(Session session) {
        if (retention == null) {
            sessions.save(session);
        } else {
            retention.admitSession(session);
        }
    }
}