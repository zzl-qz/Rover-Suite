package com.rover.agent.runtime;

import com.rover.agent.core.context.AgentContext;
import com.rover.agent.core.context.AgentContextManager;
import com.rover.agent.core.context.AgentRequestOptions;
import com.rover.agent.core.context.TargetResolution;
import com.rover.agent.core.context.TargetResolver;
import com.rover.agent.core.model.AgentMessage;
import com.rover.agent.core.model.AgentResponse;
import com.rover.agent.core.model.Incident;
import com.rover.agent.core.model.IncidentOrigin;
import com.rover.agent.core.model.MessageRole;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.TargetType;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.model.TimeRange;
import com.rover.agent.core.repository.AgentMessageRepository;
import com.rover.agent.core.repository.AgentSessionRepository;
import com.rover.agent.core.repository.AgentTaskRepository;
import com.rover.agent.core.repository.IncidentRepository;
import com.rover.agent.runtime.task.IncidentRegistry;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/**
 * Agent 应用入口：把一条用户消息编排成「上下文 → 目标 → 事件 → 任务 → 调查」的完整流程。
 *
 * 编排职责集中在这里，HTTP 契约层只做参数校验与结果映射，不再直接调用路由/实例/指标/追踪查询，
 * 也不再直接调用模型。会话连续性由三件事保证：
 * <ol>
 *   <li>上下文装配（最近 N 条消息 + 当前事件 + 结构化目标 + 关键证据）；</li>
 *   <li>目标解析（显式指定 → 现有路由/服务/实例 → 模型辅助 → 澄清）；</li>
 *   <li>事件复用（追问沿用当前事件，只有换了明确的新对象才另开事件）。</li>
 * </ol>
 *
 * 上下文的"沿用"是刻意的：解析不出新对象时沿用当前事件的目标，是会话连续性而不是猜测——
 * 只有在确实没有可继承目标时才把澄清提问回给用户，并且此时不产生任何任务。
 *
 * 用户隔离：{@code userId} 只能由后端认证上下文提供（HTTP 层从 Authentication 取），
 * 领域层不接受前端提交的用户身份。未启用登录时 {@code userId} 为 {@code null}，
 * 此时只允许访问同样没有归属的会话。
 */
public final class AgentOrchestrator {

    private static final int MAX_MESSAGE_LENGTH = 1000;
    private static final int MAX_TITLE_LENGTH = 60;

    private final AgentSessionRepository sessions;
    private final IncidentRepository incidents;
    private final AgentMessageRepository messages;
    private final AgentTaskRepository tasks;
    private final IncidentRegistry registry;
    private final AgentContextManager contexts;
    private final TargetResolver targets;
    private final InvestigationService investigations;

    public AgentOrchestrator(AgentSessionRepository sessions, IncidentRepository incidents,
                             AgentMessageRepository messages, AgentTaskRepository tasks, IncidentRegistry registry,
                             AgentContextManager contexts, TargetResolver targets,
                             InvestigationService investigations) {
        this.sessions = sessions;
        this.incidents = incidents;
        this.messages = messages;
        this.tasks = tasks;
        this.registry = registry;
        this.contexts = contexts;
        this.targets = targets;
        this.investigations = investigations;
    }

    /** 新建会话；会话归属由后端认证上下文决定。 */
    public Session startSession(String userId) {
        return startSession(userId, null);
    }

    /**
     * 新建会话，并可选指定标题（页面「新建会话」时可填）。
     * 未指定标题时留空，由第一句提问补上。
     */
    public Session startSession(String userId, String title) {
        Session session = registry.openSession(user(userId));
        String named = title == null ? "" : title.trim();
        if (named.isEmpty()) {
            return session;
        }
        String capped = named.length() > MAX_TITLE_LENGTH ? named.substring(0, MAX_TITLE_LENGTH) : named;
        return save(new Session(session.sessionId(), session.userId(), capped, session.activeIncidentId(),
                session.status(), session.createdAtMillis(), session.lastActiveAtMillis(), session.incidentIds()));
    }

    /** 当前用户的会话列表；不属于该用户的会话不可见。 */
    public List<Session> sessions(String userId) {
        return sessions.listAll().stream().filter(session -> ownedBy(session, userId)).toList();
    }

    /** 按 ID 取会话；不存在或不属于该用户时返回空。 */
    public Optional<Session> session(String sessionId, String userId) {
        return sessions.find(sessionId).filter(session -> ownedBy(session, userId));
    }

    /** 会话内的消息（最早在前）。 */
    public List<AgentMessage> conversation(String sessionId, String userId) {
        return session(sessionId, userId).map(owned -> messages.bySession(sessionId)).orElse(List.of());
    }

    /** 按 ID 取任务；任务所属会话不属于该用户时返回空。 */
    public Optional<TaskView> task(String taskId, String userId) {
        TaskView view = tasks.find(taskId).orElse(null);
        if (view == null) {
            return Optional.empty();
        }
        return sessions.find(view.sessionId()).filter(session -> ownedBy(session, userId)).map(session -> view);
    }

    /** 按 ID 取事件；事件所属会话不属于该用户时返回空。 */
    public Optional<Incident> incident(String incidentId, String userId) {
        Incident incident = incidents.find(incidentId).orElse(null);
        if (incident == null) {
            return Optional.empty();
        }
        return sessions.find(incident.sessionId()).filter(session -> ownedBy(session, userId))
                .map(owned -> incident);
    }

    /**
     * 一次性调查：新建会话后立即按显式目标提问。
     *
     * 保留给"只问一次、不需要连续追问"的旧入口使用，行为与老的提交接口一致。
     */
    public AgentResponse oneShot(String userId, ResourceTarget target, String question) {
        Session session = startSession(userId);
        return send(session.sessionId(), userId, question,
                new AgentRequestOptions(target, TimeRange.unspecified())).orElseThrow();
    }

    /**
     * 处理一条用户消息：装配上下文、解析目标、复用或新开事件、启动调查。
     *
     * @return 处理结果；会话不存在或不属于该用户时返回空（由调用方回 404）
     * @throws IllegalArgumentException 消息为空或超长
     * @throws RejectedExecutionException 任务容量已满，调用方应回 429
     */
    public Optional<AgentResponse> send(String sessionId, String userId, String message,
                                        AgentRequestOptions options) {
        String asked = validateMessage(message);
        AgentRequestOptions request = options == null ? AgentRequestOptions.none() : options;
        Optional<Session> owned = session(sessionId, userId);
        if (owned.isEmpty()) {
            return Optional.empty();
        }
        Session session = withTitle(owned.get(), asked);
        AgentContext context = contexts.build(sessionId).orElse(null);
        appendMessage(sessionId, MessageRole.USER, asked);

        TargetResolution resolution = targets.resolve(asked, request.target());
        if (resolution.needsClarification() && reuseTarget(context)) {
            // 追问沿用当前事件的目标：这是会话连续性，不是猜测（解析器本身不做这件事）。
            resolution = targets.resolve("", context.currentTarget());
        }
        if (resolution.needsClarification()) {
            AgentMessage reply = appendMessage(sessionId, MessageRole.AGENT, resolution.clarification());
            return Optional.of(new AgentResponse(touch(sessionId), null, null, reply, resolution.clarification()));
        }

        IncidentChoice choice = pickIncident(session, context, resolution.target(), request.timeRange());
        try {
            TaskView task = investigations.start(sessionId, choice.incident(), resolution.investigationPath(),
                    resolution.target(), asked);
            AgentMessage reply = appendMessage(sessionId, MessageRole.AGENT, started(choice, resolution.target()));
            return Optional.of(new AgentResponse(touch(sessionId), choice.incident(), task, reply, null));
        } catch (RejectedExecutionException ex) {
            // 只有本次新建的事件才需要撤销；沿用的事件留着，它已经有历史任务。
            if (choice.created()) {
                registry.removeIncident(choice.incident().incidentId());
            }
            throw ex;
        }
    }

    /** 目标是否可以从当前上下文继承：必须已经是一个确定的对象。 */
    private static boolean reuseTarget(AgentContext context) {
        return context != null && context.currentTarget().type() != TargetType.UNKNOWN
                && !context.currentTarget().value().isBlank();
    }

    /** 事件选择：目标与当前事件一致就沿用（含时间范围更新），否则另开新事件。 */
    private IncidentChoice pickIncident(Session session, AgentContext context, ResourceTarget target,
                                        TimeRange timeRange) {
        Incident active = context != null && context.hasActiveIncident()
                ? incidents.find(context.activeIncidentId()).orElse(null) : null;
        if (active != null && active.target().equals(target)) {
            if (timeRange.isUnspecified() || timeRange.equals(active.timeRange())) {
                return new IncidentChoice(active, false);
            }
            Incident updated = registry.updateTimeRange(active.incidentId(), timeRange);
            return new IncidentChoice(updated == null ? active : updated, false);
        }
        return new IncidentChoice(registry.openIncident(session.sessionId(), IncidentOrigin.USER, target, timeRange),
                true);
    }

    private static String started(IncidentChoice choice, ResourceTarget target) {
        String prefix = choice.created() ? "已开始调查" : "已继续调查";
        return prefix + describe(target) + "：读取路由 → 读取实例 → 读取指标 → 读取追踪。";
    }

    private static String describe(ResourceTarget target) {
        return switch (target.type()) {
            case ROUTE -> "路由 " + target.value();
            case SERVICE -> "服务 " + target.value();
            case INSTANCE -> "实例 " + target.value();
            case UNKNOWN -> "该对象";
        };
    }

    /** 落一条会话消息。 */
    private AgentMessage appendMessage(String sessionId, MessageRole role, String content) {
        AgentMessage message = new AgentMessage(UUID.randomUUID().toString(), sessionId, role, content,
                System.currentTimeMillis());
        messages.save(message);
        return message;
    }

    /** 首次提问时用问题原文生成会话标题（供会话列表展示），已有标题则不覆盖。 */
    private Session withTitle(Session session, String asked) {
        if (session.title() != null && !session.title().isBlank()) {
            return session;
        }
        String title = asked.length() > MAX_TITLE_LENGTH ? asked.substring(0, MAX_TITLE_LENGTH) : asked;
        return save(new Session(session.sessionId(), session.userId(), title, session.activeIncidentId(),
                session.status(), session.createdAtMillis(), session.lastActiveAtMillis(), session.incidentIds()));
    }

    /**
     * 刷新最近活动时间。
     *
     * 必须重新读一次再写：会话记录会被事件登记（当前事件指针）单独更新，
     * 用提问开始时读到的旧快照整体覆盖会把当前事件指针抹掉，追问就断了线。
     */
    private Session touch(String sessionId) {
        Session current = sessions.find(sessionId).orElse(null);
        if (current == null) {
            return null;
        }
        Session updated = new Session(current.sessionId(), current.userId(), current.title(),
                current.activeIncidentId(), current.status(), current.createdAtMillis(),
                System.currentTimeMillis(), current.incidentIds());
        sessions.save(updated);
        return updated;
    }

    private Session save(Session session) {
        sessions.save(session);
        return session;
    }

    /** 会话归属：未启用登录时双方都为空，视为同一（无鉴权）主体。 */
    private static boolean ownedBy(Session session, String userId) {
        return Objects.equals(session.userId(), user(userId));
    }

    private static String user(String userId) {
        return userId == null || userId.isBlank() ? null : userId.trim();
    }

    private static String validateMessage(String message) {
        String asked = message == null ? null : message.trim();
        if (asked == null || asked.isBlank() || asked.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("请输入不超过 1000 字的调查问题");
        }
        return asked;
    }

    /** 事件选择结果；{@code created} 标记本次是否新建，决定提交失败时是否需要撤销。 */
    private record IncidentChoice(Incident incident, boolean created) { }
}