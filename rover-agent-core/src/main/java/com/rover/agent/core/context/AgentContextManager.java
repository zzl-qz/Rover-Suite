package com.rover.agent.core.context;

import com.rover.agent.core.investigation.RouteMatcher;
import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.Incident;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.TargetType;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.model.TimeRange;
import com.rover.agent.core.port.InstanceReadPort;
import com.rover.agent.core.port.RouteReadPort;
import com.rover.agent.core.port.SnapshotUnavailableException;
import com.rover.agent.core.repository.AgentMessageRepository;
import com.rover.agent.core.repository.AgentSessionRepository;
import com.rover.agent.core.repository.AgentTaskRepository;
import com.rover.agent.core.repository.IncidentRepository;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import com.rover.agent.core.util.Texts;
import java.util.List;
import java.util.Optional;

/**
 * 上下文装配：把一次追问需要的历史收敛成一个有界的 {@link AgentContext}。
 *
 * 装配策略是明确的、有上限的：
 * 最近 N 条消息（N 由配置决定，不散落硬编码）+ 当前事件的结构化目标 + 当前事件的关键证据。
 * 全部历史不会被无限制地交给模型——上下文只用于让模型理解"在问哪个对象、刚才发生过什么"，
 * 事实判定仍以本次调查采集到的证据为准。
 *
 * 目标是展开的：路由目标会带出它的目标服务名，实例目标会带出它所属服务名，方便后续追问
 * 用"那个服务"指代。只读数据源不可用时展开值留空，不猜测。
 */
public final class AgentContextManager {

    /** 关键证据条数上限：证据只用于补足上下文，超出部分不进入窗口。 */
    private static final int MAX_EVIDENCE = 10;

    private final AgentSessionRepository sessions;
    private final IncidentRepository incidents;
    private final AgentMessageRepository messages;
    private final AgentTaskRepository tasks;
    private final RouteReadPort routes;
    private final InstanceReadPort instances;
    private final int recentMessageLimit;

    /**
     * @param recentMessageLimit 上下文里保留的最近消息条数；小于 1 时按 1 处理
     */
    public AgentContextManager(AgentSessionRepository sessions, IncidentRepository incidents,
                               AgentMessageRepository messages, AgentTaskRepository tasks, RouteReadPort routes,
                               InstanceReadPort instances, int recentMessageLimit) {
        this.sessions = sessions;
        this.incidents = incidents;
        this.messages = messages;
        this.tasks = tasks;
        this.routes = routes;
        this.instances = instances;
        this.recentMessageLimit = Math.max(1, recentMessageLimit);
    }

    /** 当前保留的最近消息条数上限。 */
    public int recentMessageLimit() {
        return recentMessageLimit;
    }

    /** 装配会话上下文；会话不存在时返回空，由调用方回 404。 */
    public Optional<AgentContext> build(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        Optional<Session> session = sessions.find(sessionId);
        if (session.isEmpty()) {
            return Optional.empty();
        }
        Incident incident = activeIncident(session.get()).orElse(null);
        ResourceTarget target = incident == null ? ResourceTarget.unknown() : incident.target();
        TimeRange range = incident == null ? TimeRange.unspecified() : incident.timeRange();
        return Optional.of(new AgentContext(session.get().sessionId(),
                incident == null ? null : incident.incidentId(), target, serviceOf(target), routeOf(target),
                instanceOf(target), range, tail(messages.bySession(sessionId), recentMessageLimit),
                importantEvidence(incident)));
    }

    private Optional<Incident> activeIncident(Session session) {
        String incidentId = session.activeIncidentId();
        return incidentId == null || incidentId.isBlank() ? Optional.empty() : incidents.find(incidentId);
    }

    /** 路由目标的服务名来自 Gateway 路由表；服务与实例目标本身就指向服务。 */
    private String serviceOf(ResourceTarget target) {
        if (target.type() == TargetType.SERVICE) {
            return target.value();
        }
        if (target.type() == TargetType.ROUTE) {
            RouteSnapshot matched = RouteMatcher.match(readRoutes(), target.value());
            String serviceName = matched == null ? "" : Texts.orEmpty(matched.serviceName());
            return serviceName.isBlank() ? null : serviceName;
        }
        if (target.type() == TargetType.INSTANCE) {
            return readInstances().stream()
                    .filter(instance -> address(instance).equalsIgnoreCase(target.value()))
                    .map(instance -> Texts.orEmpty(instance.serviceName()))
                    .filter(name -> !name.isBlank())
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    private static String routeOf(ResourceTarget target) {
        return target.type() == TargetType.ROUTE && !target.value().isBlank() ? target.value() : null;
    }

    private static String instanceOf(ResourceTarget target) {
        return target.type() == TargetType.INSTANCE && !target.value().isBlank() ? target.value() : null;
    }

    /** 关键证据取当前事件里最新一次调查的证据，只取尾部若干条。 */
    private List<Evidence> importantEvidence(Incident incident) {
        if (incident == null || incident.taskIds() == null || incident.taskIds().isEmpty()) {
            return List.of();
        }
        TaskView latest = null;
        for (String taskId : incident.taskIds()) {
            TaskView view = tasks.find(taskId).orElse(null);
            if (view != null && (latest == null || view.createdAtMillis() > latest.createdAtMillis())) {
                latest = view;
            }
        }
        if (latest == null || latest.result() == null || latest.result().evidence() == null) {
            return List.of();
        }
        return tail(latest.result().evidence(), MAX_EVIDENCE);
    }

    private static <T> List<T> tail(List<T> values, int limit) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, values.size() - limit);
        return List.copyOf(values.subList(from, values.size()));
    }

    private List<RouteSnapshot> readRoutes() {
        try {
            List<RouteSnapshot> snapshot = routes == null ? null : routes.routes();
            return snapshot == null ? List.of() : snapshot;
        } catch (SnapshotUnavailableException ex) {
            return List.of();
        }
    }

    private List<InstanceSnapshot> readInstances() {
        try {
            List<InstanceSnapshot> snapshot = instances == null ? null : instances.instances();
            return snapshot == null ? List.of() : snapshot;
        } catch (SnapshotUnavailableException ex) {
            return List.of();
        }
    }

    private static String address(InstanceSnapshot instance) {
        return Texts.orEmpty(instance.host()) + ":" + instance.port();
    }

}