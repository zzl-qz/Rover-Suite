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
import com.rover.agent.core.model.InvestigationReport;
import com.rover.agent.core.model.MessageRole;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.StepStatus;
import com.rover.agent.core.model.TargetType;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.model.TimeRange;
import com.rover.agent.core.recall.OpeningFilter;
import com.rover.agent.core.repository.AgentMessageRepository;
import com.rover.agent.core.repository.AgentSessionRepository;
import com.rover.agent.core.repository.AgentTaskRepository;
import com.rover.agent.core.repository.IncidentRepository;
import com.rover.agent.runtime.journal.DialogueCards;
import com.rover.agent.runtime.journal.OpsJournal;
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
 * Agent 应用入口：接收用户消息，异步交给对话主路径处理，并把结论落回会话。
 *
 * 这里只有两条执行路径，彼此不共用判据：
 * <ul>
 *   <li>人工消息：{@code submit} → Worker → 背景构建 → 目标 best-effort 绑定 → 对话主路径
 *       （模型自主决定调用哪些只读工具）；</li>
 *   <li>机器事件：{@link #ingestAlert} → {@link InvestigationService} → 有界自动调查图。</li>
 * </ul>
 *
 * 不存在「先判断这句话属于查询、解释还是调查，再分流」的中间层：那正是被证明会限制自然语言
 * 灵活性、并已从主链上撤下的旧做法。
 */
public final class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private static final int MAX_MESSAGE_LENGTH = 1000;
    private static final int MAX_TITLE_LENGTH = 60;

    private static final String STEP_TARGET = "目标解析";

    private final AgentSessionRepository sessions;
    private final IncidentRepository incidents;
    private final AgentMessageRepository messages;
    private final AgentTaskRepository tasks;
    private final IncidentRegistry registry;
    private final AgentContextManager contexts;
    private final TargetResolver targets;
    private final InvestigationService investigations;
    private final WorkspaceRetention retention;
    private final ToolLoopService toolLoop;
    private final OpsJournal journal;
    private final DialogueCards dialogue;

    public AgentOrchestrator(AgentSessionRepository sessions, IncidentRepository incidents,
                             AgentMessageRepository messages, AgentTaskRepository tasks, IncidentRegistry registry,
                             AgentContextManager contexts, TargetResolver targets,
                             InvestigationService investigations, WorkspaceRetention retention,
                             ToolLoopService toolLoop, OpsJournal journal) {
        this.sessions = sessions;
        this.incidents = incidents;
        this.messages = messages;
        this.tasks = tasks;
        this.registry = registry;
        this.contexts = contexts;
        this.targets = targets;
        this.investigations = investigations;
        this.retention = retention;
        this.toolLoop = toolLoop;
        this.journal = journal == null ? OpsJournal.none() : journal;
        this.dialogue = new DialogueCards(sessions, messages, tasks);
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

    /** Agent Worker 的执行段：组织背景后交给模型自主查询与作答；任何异常都在这里收敛成任务失败，不抛回线程池。 */
    private void resolveAndRun(InvestigationTask task, AgentRequestOptions options) {
        task.start();
        task.markRunning();
        try {
            if (task.cancelled()) {
                return;
            }
            Session session = sessions.find(task.sessionId()).orElse(null);
            if (session == null) {
                task.fail("会话不存在");
                return;
            }
            String forcedSession = options == null ? null : options.recallSessionId();
            DialogueCards.Outcome earlier = dialogue.recall(session, task.question(), task.taskId(),
                    forcedSession, System.currentTimeMillis());
            if (earlier.kind() != DialogueCards.Kind.NONE) {
                task.markConversation();
                task.step(AgentStepType.MEMORY, "对话回顾", StepStatus.COMPLETED,
                        earlier.choices().isEmpty() ? "带上点名的那场结论，没有加载原文" : "昨天有多场，只列出标题");
                task.answerRecall(earlier.text(), earlier.choices());
                appendReply(task);
                return;
            }
            AgentContext context = contexts.build(task.sessionId()).orElse(null);
            bindTargetBestEffort(task, session, context, options);
            if (task.cancelled()) {
                return;
            }
            task.markConversation();
            String recalled = journal.recall(subjectKey(task), task.question());
            if (!recalled.isBlank()) {
                task.step(AgentStepType.MEMORY, "资源笔记", StepStatus.COMPLETED, "带上该资源上次已确认的结论");
            }
            String background = contextSummary(context);
            String opening = dialogue.opening(task.sessionId(),
                    context == null ? List.of() : context.recentMessages());
            if (!opening.isBlank()) {
                background = background.isBlank() ? opening : opening + "\n" + background;
            }
            if (!recalled.isBlank()) {
                background = background.isBlank() ? recalled : background + "\n" + recalled;
            }
            toolLoop.run(task, background);
            if (!task.cancelled()) {
                journal.record(task.view());
            }
            appendReply(task);
            appendConclusion(task);
        } catch (Exception ex) {
            if (task.cancelled()) {
                return;
            }
            log.error("Agent 任务执行异常", ex);
            task.fail("任务执行失败");
        } finally {
            task.clearRunning();
        }
    }

    /**
     * 取消一个仍在执行中的任务；任务不存在、不属于该用户或已终态时返回 false（由调用方映射为 404 / 409）。
     *
     * 取消是协作式的：标记取消并中断执行线程，执行线程在下一个检查点停下，不再产出结论。
     */
    public boolean cancel(String taskId, String userId) {
        return task(taskId, userId).map(view -> {
            if (!view.status().active()) {
                return false;
            }
            investigations.cancel(taskId);
            return true;
        }).orElse(false);
    }

    /**
     * 事件接入：告警 / 网关切面异常等事件触发一次自动调查。
     *
     * 这是机器事件的唯一入口，走的是与人工会话不同的执行形态：没有模型自主选工具这一步，
     * 而是由规划器产出只读步骤、执行器按硬边界执行。取数口与人工会话相同（都经 CapabilityExecutor），
     * 事件来源记为 {@code ALERT}，且可选自带观测窗口；窗口不传则按各数据源默认窗口取数。
     * 会话按事件独立开（无归属用户），不会与人工会话争「单活跃任务」锁。
     *
     * @return 已受理的调查任务视图（异步执行，可凭 taskId 轮询 / 订阅事件）
     */
    public TaskView ingestAlert(String path, String alertMessage, Long fromMillis, Long toMillis) {
        TimeRange range = (fromMillis != null && toMillis != null)
                ? new TimeRange(fromMillis, toMillis)
                : TimeRange.unspecified();
        return investigations.submitAlert(path, alertMessage, range);
    }

    /** 尽力识别问题里点到的对象：识别出来就挂到对应事件下（同对象续用、换对象另开），识别不出也不拦。 */
    private void bindTargetBestEffort(InvestigationTask task, Session session, AgentContext context,
                                      AgentRequestOptions options) {
        TargetResolution resolution;
        try {
            resolution = resolveTarget(task.question(), context, options.target());
        } catch (Exception ex) {
            log.warn("目标解析失败，按无对象继续对话", ex);
            bindCurrentIncident(task, context);
            return;
        }
        if (resolution.needsClarification()) {
            bindCurrentIncident(task, context);
            return;
        }
        Incident incident = pickIncident(session, context, resolution.target(), options.timeRange());
        registry.attachTask(incident.incidentId(), task.taskId());
        task.bind(incident.incidentId(), resolution.investigationPath(), resolution.target());
        task.step(AgentStepType.TARGET_RESOLUTION, STEP_TARGET, StepStatus.COMPLETED,
                "已识别对话对象：" + describe(resolution.target()));
    }

    /** 结论回写事件，让追问能继承最新结论；回写失败静默跳过。 */
    private void appendConclusion(InvestigationTask task) {
        InvestigationReport report = task.view().result();
        if (report == null || report.summary().isBlank() || task.incidentId() == null) {
            return;
        }
        try {
            registry.summarise(task.incidentId(), report.summary());
        } catch (Exception ex) {
            log.warn("Agent 结论回写事件失败：incidentId={}", task.incidentId(), ex);
        }
    }

    /** 目标解析：显式指定 → 现有对象 → 模型辅助；解析不出时按会话上下文沿用（追问连续性）。 */
    private TargetResolution resolveTarget(String question, AgentContext context, ResourceTarget explicit) {
        TargetResolution resolution = targets.resolve(question, explicit);
        if (resolution.needsClarification() && reuseTarget(context)) {
            // 追问沿用当前事件的目标：这是会话连续性，不是猜测（解析器本身不做这件事）。
            resolution = targets.resolve("", context.currentTarget());
        }
        return resolution;
    }

    /**
     * 没识别出对象时的绑定：只沿用当前事件（若有），不新建事件、不挂任务、不覆盖事件结论。
     *
     * 挂在同一事件下，会话的追问才连得上；而一句没点对象的问候不该把上一轮的事件重新拉起来。
     */
    private void bindCurrentIncident(InvestigationTask task, AgentContext context) {
        if (context == null || !context.hasActiveIncident()) {
            return;
        }
        task.bind(context.activeIncidentId(), "", ResourceTarget.unknown());
    }

    /** 结论落成一条 Agent 回复：任务失败或没有结论时静默跳过，不伪造回复。 */
    private void appendReply(InvestigationTask task) {
        InvestigationReport report = task.view().result();
        if (report == null || report.summary().isBlank()) {
            return;
        }
        appendMessage(task.sessionId(), MessageRole.AGENT, report.summary(), task.taskId());
    }

    /** 对话主路径的背景说明：只给当前对象与最近的用户消息，不夹带任何受控事实。 */
    private static String contextSummary(AgentContext context) {
        if (context == null) {
            return "";
        }
        StringBuilder summary = new StringBuilder();
        if (context.currentTarget().type() != TargetType.UNKNOWN && !context.currentTarget().value().isBlank()) {
            summary.append("当前对象：").append(describe(context.currentTarget())).append("；");
        }
        String asked = context.recentMessages().stream()
                .filter(message -> message.role() == MessageRole.USER)
                .map(AgentMessage::content)
                .reduce((left, right) -> left + " / " + right)
                .orElse("");
        if (!asked.isBlank()) {
            summary.append("最近的用户消息：").append(asked);
        }
        return summary.toString();
    }

    /** 目标是否可以从当前上下文继承：必须已经是一个确定的对象。 */
    private static boolean reuseTarget(AgentContext context) {
        return context != null && context.currentTarget().type() != TargetType.UNKNOWN
                && !context.currentTarget().value().isBlank();
    }

    /** 事件选择：目标与当前事件一致就沿用（含时间范围更新），否则另开新事件。 */
    private Incident pickIncident(Session session, AgentContext context, ResourceTarget target,
                                  TimeRange timeRange) {
        Incident active = context != null && context.hasActiveIncident()
                ? incidents.find(context.activeIncidentId()).orElse(null) : null;
        if (active != null && active.target().equals(target)) {
            if (timeRange.isUnspecified() || timeRange.equals(active.timeRange())) {
                return active;
            }
            return registry.updateTimeRange(active.incidentId(), timeRange);
        }
        return registry.openIncident(session.sessionId(), IncidentOrigin.USER, target, timeRange);
    }

    /** 记忆按路由或服务归集。实例只挂在事件上，不单独做服务结论的键。 */
    private static String subjectKey(InvestigationTask task) {
        ResourceTarget target = task.target();
        if (target == null || target.type() == TargetType.UNKNOWN || target.value().isBlank()) {
            return null;
        }
        return target.type() == TargetType.INSTANCE ? null : target.value();
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

    /**
     * 会话标题取第一句具体问题。
     *
     * 开头若只是寒暄，先用那句占位；等真正的问题出现再换成它。已经是具体问题的标题不再改。
     */
    private Session withTitle(Session session, String asked) {
        String current = session.title();
        boolean missing = current == null || current.isBlank();
        if (!missing && !OpeningFilter.aside(current)) {
            return session;
        }
        if (!missing && OpeningFilter.aside(asked)) {
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

    /**
     * Workbench 聚合视图。
     *
     * {@code tasks} 只是最近若干条：窗口外的历史任务不在本次响应里，响应结构保持对象形态，
     * 之后接入持久化时可以在同一层加游标字段而不破坏调用方。
     */
    public record Workspace(Session session, List<AgentMessage> messages, List<Incident> incidents,
                            List<TaskView> tasks, Incident activeIncident) { }
}