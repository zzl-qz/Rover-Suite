package com.rover.agent.runtime;

import com.rover.agent.core.context.AgentContext;
import com.rover.agent.core.context.AgentContextManager;
import com.rover.agent.core.context.AgentRequestOptions;
import com.rover.agent.core.context.TargetResolution;
import com.rover.agent.core.context.TargetResolver;
import com.rover.agent.core.event.TaskEventSubscriber;
import com.rover.agent.core.event.TaskEventSubscription;
import com.rover.agent.core.model.AgentMessage;
import com.rover.agent.core.model.AgentStepType;
import com.rover.agent.core.model.Incident;
import com.rover.agent.core.model.IncidentOrigin;
import com.rover.agent.core.model.MessageRole;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.StepStatus;
import com.rover.agent.core.model.TargetType;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.model.TimeRange;
import com.rover.agent.core.repository.AgentMessageRepository;
import com.rover.agent.core.repository.AgentSessionRepository;
import com.rover.agent.core.repository.AgentTaskRepository;
import com.rover.agent.core.repository.IncidentRepository;
import com.rover.agent.runtime.task.IncidentRegistry;
import com.rover.agent.runtime.task.InvestigationTask;
import com.rover.agent.runtime.task.WorkspaceRetention;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 应用入口：把一条用户消息编排成「上下文 → 目标 → 事件 → 任务 → 调查」的完整流程。
 *
 * 提交是异步的：{@link #submit} 只做参数校验、会话归属检查、落用户消息与登记 PENDING 任务，
 * 随即把「目标解析 + 事件选择 + 调查执行」整段交给 Agent Worker。这样 Servlet 线程不会被
 * 管理口 HTTP（路由/实例快照）或模型调用拖住，浏览器拿到的只是任务句柄，进度经由任务快照与
 * 任务事件观察。
 *
 * 会话连续性由三件事保证（全部在 Worker 里完成）：
 * <ol>
 *   <li>上下文装配（最近 N 条消息 + 当前事件 + 结构化目标 + 关键证据）；</li>
 *   <li>目标解析（显式指定 → 现有路由/服务/实例 → 模型辅助 → 澄清）；</li>
 *   <li>事件复用（追问沿用当前事件，只有换了明确的新对象才另开事件）。</li>
 * </ol>
 *
 * 上下文的"沿用"是刻意的：解析不出新对象时沿用当前事件的目标，是会话连续性而不是猜测——
 * 只有在确实没有可继承目标时才把澄清提问回给用户，此时任务停在 {@code WAITING_INPUT}，
 * 既不占用会话并发位，也不用一轮空转调查冒充结论。
 *
 * 用户隔离：{@code userId} 只能由后端认证上下文提供（HTTP 层从 Authentication 取），
 * 领域层不接受前端提交的用户身份。未启用登录时 {@code userId} 为 {@code null}，
 * 此时只允许访问同样没有归属的会话。
 */
public final class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private static final int MAX_MESSAGE_LENGTH = 1000;
    private static final int MAX_TITLE_LENGTH = 60;

    private static final String STEP_TARGET = "目标解析";
    private static final String TARGET_RUNNING = "根据问题与会话上下文确定调查对象";
    private static final String TARGET_NEEDS_INPUT = "未能确定调查对象，需要用户补充信息";

    private final AgentSessionRepository sessions;
    private final IncidentRepository incidents;
    private final AgentMessageRepository messages;
    private final AgentTaskRepository tasks;
    private final IncidentRegistry registry;
    private final AgentContextManager contexts;
    private final TargetResolver targets;
    private final InvestigationService investigations;
    private final WorkspaceRetention retention;

    public AgentOrchestrator(AgentSessionRepository sessions, IncidentRepository incidents,
                             AgentMessageRepository messages, AgentTaskRepository tasks, IncidentRegistry registry,
                             AgentContextManager contexts, TargetResolver targets,
                             InvestigationService investigations, WorkspaceRetention retention) {
        this.sessions = sessions;
        this.incidents = incidents;
        this.messages = messages;
        this.tasks = tasks;
        this.registry = registry;
        this.contexts = contexts;
        this.targets = targets;
        this.investigations = investigations;
        this.retention = retention;
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
        return sessions.findByUserId(user(userId));
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
     * Workbench 聚合视图：会话、对话、事件与最近任务一次取齐，前端不必逐个任务轮询。
     *
     * 只做读模型聚合，不引入新的状态：任务只返回最近 {@code taskLimit} 条（新的在前），
     * 完整时间线仍由任务详情按需取；未登录时与会话详情一样只认无归属的会话。
     */
    public Optional<Workspace> workspace(String sessionId, String userId, int taskLimit) {
        return session(sessionId, userId).map(owned -> new Workspace(owned, messages.bySession(sessionId),
                incidents.bySession(sessionId), tasks.recentBySession(sessionId, taskLimit),
                owned.activeIncidentId() == null ? null : incidents.find(owned.activeIncidentId()).orElse(null)));
    }

    /**
     * 订阅任务事件：任务不存在或所属会话不属于该用户时返回空（由调用方回 404）。
     *
     * 权限校验在这里统一完成——事件流与任务详情看到的是同一份归属判定，
     * 调用方（HTTP 层）不再自己查任务。
     */
    public Optional<TaskEventSubscription> subscribeEvents(String taskId, String userId,
                                                           TaskEventSubscriber subscriber) {
        if (task(taskId, userId).isEmpty()) {
            return Optional.empty();
        }
        return investigations.subscribeEvents(taskId, subscriber);
    }

    /**
     * 一次性调查：新建会话后立即按显式目标提问。
     *
     * 保留给"只问一次、不需要连续追问"的旧入口使用，行为与老的提交接口一致。
     */
    public TaskView oneShot(String userId, ResourceTarget target, String question) {
        Session session = startSession(userId);
        return submit(session.sessionId(), userId, question,
                new AgentRequestOptions(target, TimeRange.unspecified())).orElseThrow();
    }

    /**
     * 提交一条用户消息：校验 → 落用户消息 → 登记 PENDING 任务 → 入队，随即返回任务句柄。
     *
     * 目标解析、事件选择与调查执行全部在 Agent Worker 中进行，本方法不做任何阻塞调用。
     *
     * @return 任务视图（PENDING）；会话不存在或不属于该用户时返回空（由调用方回 404）
     * @throws IllegalArgumentException                                     消息为空或超长
     * @throws com.rover.agent.runtime.task.SessionTaskRunningException    同会话已有执行中的任务（调用方回 409）
     * @throws RejectedExecutionException                                  任务容量或队列已满（调用方回 429）
     */
    public Optional<TaskView> submit(String sessionId, String userId, String message,
                                     AgentRequestOptions options) {
        String asked = validateMessage(message);
        AgentRequestOptions request = options == null ? AgentRequestOptions.none() : options;
        Optional<Session> owned = session(sessionId, userId);
        if (owned.isEmpty()) {
            return Optional.empty();
        }
        InvestigationTask task = investigations.register(sessionId, asked);
        appendMessage(sessionId, MessageRole.USER, asked, task.taskId());
        withTitle(owned.get(), asked);
        touch(sessionId);
        try {
            investigations.execute(() -> resolveAndRun(task, request));
        } catch (RejectedExecutionException ex) {
            // 队列满：回滚登记。用户消息已经落库，它是「用户说过这句话」的真实记录，保留不动。
            investigations.discard(task.taskId());
            throw ex;
        }
        return Optional.of(task.view());
    }

    /**
     * Agent Worker 的完整执行段：目标解析 → 事件选择 → 调查执行。
     *
     * 任何异常都在这里收敛成任务失败，绝不把异常抛回线程池（否则任务会永远停在 RUNNING）。
     */
    private void resolveAndRun(InvestigationTask task, AgentRequestOptions options) {
        try {
            task.start();
            task.step(AgentStepType.TARGET_RESOLUTION, STEP_TARGET, StepStatus.RUNNING, TARGET_RUNNING);

            AgentContext context = contexts.build(task.sessionId()).orElse(null);
            TargetResolution resolution = targets.resolve(task.question(), options.target());
            if (resolution.needsClarification() && reuseTarget(context)) {
                // 追问沿用当前事件的目标：这是会话连续性，不是猜测（解析器本身不做这件事）。
                resolution = targets.resolve("", context.currentTarget());
            }
            if (resolution.needsClarification()) {
                task.step(AgentStepType.TARGET_RESOLUTION, STEP_TARGET, StepStatus.COMPLETED, TARGET_NEEDS_INPUT);
                appendMessage(task.sessionId(), MessageRole.AGENT, resolution.clarification(), task.taskId());
                task.waitForInput(resolution.clarification());
                return;
            }

            Session session = sessions.find(task.sessionId()).orElse(null);
            if (session == null) {
                task.fail("会话不存在");
                return;
            }
            IncidentChoice choice = pickIncident(session, context, resolution.target(), options.timeRange());
            registry.attachTask(choice.incident().incidentId(), task.taskId());
            task.bind(choice.incident().incidentId(), resolution.investigationPath(), resolution.target());
            task.step(AgentStepType.TARGET_RESOLUTION, STEP_TARGET, StepStatus.COMPLETED,
                    "已确定调查对象：" + describe(resolution.target()));
            appendMessage(task.sessionId(), MessageRole.AGENT, started(choice, resolution.target()),
                    task.taskId());
            investigations.run(task);
        } catch (Exception ex) {
            log.error("Agent 目标解析或调查执行异常", ex);
            task.fail("调查任务执行失败");
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

    /** 落一条会话消息，并绑定触发它的任务（会话时间线的排序依据）；消息容量由保留策略管理。 */
    private AgentMessage appendMessage(String sessionId, MessageRole role, String content, String relatedTaskId) {
        AgentMessage message = new AgentMessage(UUID.randomUUID().toString(), sessionId, role, content,
                System.currentTimeMillis(), relatedTaskId);
        retention.admitMessage(message);
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

    /**
     * Workbench 聚合视图。
     *
     * {@code tasks} 只是最近若干条：窗口外的历史任务不在本次响应里，响应结构保持对象形态，
     * 之后接入持久化时可以在同一层加游标字段而不破坏调用方。
     */
    public record Workspace(Session session, List<AgentMessage> messages, List<Incident> incidents,
                            List<TaskView> tasks, Incident activeIncident) { }
}