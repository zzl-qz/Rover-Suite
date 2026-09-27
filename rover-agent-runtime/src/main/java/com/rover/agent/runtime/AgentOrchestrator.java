package com.rover.agent.runtime;

import com.rover.agent.core.context.AgentContext;
import com.rover.agent.core.context.AgentContextManager;
import com.rover.agent.core.context.AgentRequestOptions;
import com.rover.agent.core.context.TargetResolution;
import com.rover.agent.core.context.TargetResolver;
import com.rover.agent.core.event.TaskEventSubscriber;
import com.rover.agent.core.event.TaskEventSubscription;
import com.rover.agent.core.intent.IntentClassifier;
import com.rover.agent.core.intent.IntentService;
import com.rover.agent.core.intent.QuerySubject;
import com.rover.agent.core.model.ActionType;
import com.rover.agent.core.model.AgentIntent;
import com.rover.agent.core.model.AgentMessage;
import com.rover.agent.core.model.AgentStepType;
import com.rover.agent.core.model.Confidence;
import com.rover.agent.core.model.Incident;
import com.rover.agent.core.model.IncidentOrigin;
import com.rover.agent.core.model.IntentDecision;
import com.rover.agent.core.model.IntentTopic;
import com.rover.agent.core.model.InvestigationReport;
import com.rover.agent.core.model.MessageRole;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.StepStatus;
import com.rover.agent.core.model.TargetType;
import com.rover.agent.core.model.TaskType;
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

/** Agent 应用入口：接收用户消息，异步交给对话主路径处理，并把结论落回会话。 */
public final class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private static final int MAX_MESSAGE_LENGTH = 1000;
    private static final int MAX_TITLE_LENGTH = 60;

    private static final String STEP_INTENT = "意图识别";
    private static final String INTENT_RUNNING = "根据问题与会话上下文判断用户想做什么";
    private static final String STEP_TARGET = "目标解析";
    private static final String TARGET_RUNNING = "根据问题与会话上下文确定调查对象";
    private static final String TARGET_NEEDS_INPUT = "未能确定调查对象，需要用户补充信息";
    private static final String STEP_ANSWER = "回答";

    /** 未开放能力的回复：如实说明边界，并给出当前确实能做的事。 */
    private static final String UNSUPPORTED_INSPECTION =
            "定时巡检尚未开放：当前版本不接入调度与通知，无法创建周期任务。可以先手动提问，或让我对该服务做一次只读调查。";
    private static final String UNSUPPORTED_KNOWLEDGE =
            "文档与教程检索还没接入，所以「怎么配置」这类使用说明我查不到；"
                    + "但配置的当前生效值我能直接读出来——说清是哪一项（限流、熔断、超时、采样率等），我就去 Gateway 与 NameServer 上取。"
                    + "路由、实例、指标、追踪、注册事件的事实性问题，以及调用失败排查，也都可以直接问我。";
    private static final String UNSUPPORTED_NOTE =
            "能力边界由 CapabilityRegistry 声明：未开放的能力不会被 Agent 调用，也不会被模拟执行。";

    private static final String UNKNOWN_TARGET_FALLBACK = "未识别出明确意图，但问题指向了可解析的对象，按故障调查处理";
    private static final String UNKNOWN_INTRO_STEP = "未能识别意图，也没有可解析的对象，改为说明 Agent 身份与能力";
    private static final String UNKNOWN_INTRO_FALLBACK = "未能识别用户想做什么，先说明 Agent 的身份、能力与提问方式";

    /** 路径纠正的步骤名：轻量路径给不出有用结果时改走调查，这一步让用户看到「为什么换了形态」。 */
    private static final String STEP_CORRECTION = "路径纠正";
    private static final String EXPLAIN_WITHOUT_CONTEXT = "问题要求解释，但会话里还没有可解释的调查结论";
    private static final String QUERY_WITHOUT_SUBJECT = "问题要求查询状态，但没能确定要查哪一类事实";
    private static final String CORRECTION_SUFFIX = "；问题指向的对象可解析，改为按故障调查执行";

    /**
     * 对话形态的意图说明：这条路径上没有「识别出的意图」这回事——查什么、怎么答都由模型在对话中决定。
     * 用一个固定的说明性判断代替，是为了让任务视图仍有可展示的依据，而不是留一片空白。
     */
    private static final IntentDecision CONVERSATION_DECISION = new IntentDecision(
            AgentIntent.INVESTIGATE, Confidence.MEDIUM, IntentTopic.NONE, "", TimeRange.unspecified(),
            ActionType.UNKNOWN, "对话主路径：查什么与怎么答由模型在对话中自主决定", false, null);

    private final AgentSessionRepository sessions;
    private final IncidentRepository incidents;
    private final AgentMessageRepository messages;
    private final AgentTaskRepository tasks;
    private final IncidentRegistry registry;
    private final AgentContextManager contexts;
    private final TargetResolver targets;
    private final InvestigationService investigations;
    private final WorkspaceRetention retention;
    private final IntentService intents;
    private final QueryStateService queries;
    private final ExplainService explanations;
    private final ActionPlanService actionPlans;
    private final ToolLoopService toolLoop;

    public AgentOrchestrator(AgentSessionRepository sessions, IncidentRepository incidents,
                             AgentMessageRepository messages, AgentTaskRepository tasks, IncidentRegistry registry,
                             AgentContextManager contexts, TargetResolver targets,
                             InvestigationService investigations, WorkspaceRetention retention,
                             IntentService intents, QueryStateService queries, ExplainService explanations,
                             ActionPlanService actionPlans, ToolLoopService toolLoop) {
        this.sessions = sessions;
        this.incidents = incidents;
        this.messages = messages;
        this.tasks = tasks;
        this.registry = registry;
        this.contexts = contexts;
        this.targets = targets;
        this.investigations = investigations;
        this.retention = retention;
        this.intents = intents == null ? new IntentService() : intents;
        this.queries = queries;
        this.explanations = explanations;
        this.actionPlans = actionPlans;
        this.toolLoop = toolLoop;
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

    /**
     * Agent Worker 的完整执行段：组织背景 → 交给模型自主查询与作答 → 结论落会话。
     *
     * <p>这里不再有「先归类意图、再按分类执行」这一步。那条路径的失败模式是「归类不了就整句作废」：
     * 稍微绕一点的问题落不进任何词表，得到的回答就是一句「我没太明白」。现在决策权交给模型——
     * 它看到的是问题本身和一组可执行工具，自己决定查什么、查几次、怎么答。
     *
     * <p>任何异常都在这里收敛成任务失败，绝不把异常抛回线程池（否则任务会永远停在 RUNNING）。
     */
    private void resolveAndRun(InvestigationTask task, AgentRequestOptions options) {
        try {
            task.start();
            Session session = sessions.find(task.sessionId()).orElse(null);
            if (session == null) {
                task.fail("会话不存在");
                return;
            }
            AgentContext context = contexts.build(task.sessionId()).orElse(null);
            bindTargetBestEffort(task, session, context, options);
            task.classify(TaskType.CONVERSATION, CONVERSATION_DECISION);
            toolLoop.run(task, contextSummary(context));
            appendReply(task);
            appendConclusion(task);
        } catch (Exception ex) {
            log.error("Agent 任务执行异常", ex);
            task.fail("任务执行失败");
        }
    }

    /**
     * 尽力识别问题里点到的对象：识别出来就按它聚合，识别不出也不拦。
     *
     * <p>目标解析在这里不再是「能不能继续」的闸门，只决定这次对话挂在哪个事件下——追问「它呢」时，
     * 模型需要知道上一轮说的是哪个服务或哪条路径。具体查什么仍由模型自己决定，
     * 它完全可以忽略这个背景，直接按问题里的说法取数。
     *
     * <p>识别出对象时沿用事件选择规则（同对象续用、换对象另开）：事件仍是「同一对象的多次对话」
     * 的聚合点，否则会话连续性会断在这里。
     */
    private void bindTargetBestEffort(InvestigationTask task, Session session, AgentContext context,
                                      AgentRequestOptions options) {
        TargetResolution resolution;
        try {
            resolution = resolveTarget(task.question(), context, options.target());
        } catch (Exception ex) {
            log.warn("目标解析失败，按无对象继续对话", ex);
            bindCurrentIncident(task, context, "", ResourceTarget.unknown());
            return;
        }
        if (resolution.needsClarification()) {
            bindCurrentIncident(task, context, "", ResourceTarget.unknown());
            return;
        }
        IncidentChoice choice = pickIncident(session, context, resolution.target(), options.timeRange());
        registry.attachTask(choice.incident().incidentId(), task.taskId());
        task.bind(choice.incident().incidentId(), resolution.investigationPath(), resolution.target());
        task.step(AgentStepType.TARGET_RESOLUTION, STEP_TARGET, StepStatus.COMPLETED,
                "已识别对话对象：" + describe(resolution.target()));
    }

    /**
     * 结论回写事件：事件因此是「同一对象的多次对话」的聚合点，追问时能继承最新结论。
     *
     * <p>发不了结论时静默跳过——回写失败不该把一次已经完成的对话判成失败。
     */
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

    /**
     * 解释用例：讲能力，或复用会话里已有的调查结论。
     *
     * 既不是能力咨询、会话里又没有可解释的结论时，如果问题点名了一个能解析出来的对象，
     * 那说明用户问的其实是「这个对象是怎么回事」——回一句「先提出一个具体问题」是把话堵死，
     * 改为按故障调查执行一次；没有对象线索（「总结一下」）时仍按原路径如实说明。
     */
    private void runExplain(InvestigationTask task, Session session, AgentContext context, IntentDecision decision,
                            AgentRequestOptions options) {
        if (decision.topic() != IntentTopic.CAPABILITIES && !explanations.hasConclusion(task.sessionId())) {
            // 形似「解释」实为「查询」：句子里点名了某类可查事实（实例 / 路由 / 指标 / 配置 / 事件）时，
            // 用户要的是那类事实本身，而不是一段说明。这类问题若按解释走只会回一句「对不上」，
            // 可它其实完全答得出来。先纠到查询路径，再考虑要不要升级为调查。
            QuerySubject subject = IntentClassifier.stateSubject(task.question());
            if (subject != QuerySubject.NONE) {
                task.step(AgentStepType.INTENT_RESOLUTION, STEP_CORRECTION, StepStatus.COMPLETED,
                        "问题里点名了可查的事实口径（" + subject + "），改为按状态查询执行");
                // 任务类型必须先改过来：编排入口已按解释分类过，只换执行路径会让界面上写着
                // 「解释说明」却在查实例——任务类型是给用户看的执行形态，必须与实际动作一致。
                task.classify(TaskType.QUERY, decision.as(AgentIntent.QUERY_STATE, Confidence.MEDIUM,
                        "问题里点名了「" + subject + "」这一可查口径，按状态查询执行"));
                runQuery(task, session, context, decision, options);
                return;
            }
            if (hasTargetClue(decision, options)
                    && escalateToInvestigation(task, session, context, decision, options, EXPLAIN_WITHOUT_CONTEXT)) {
                return;
            }
        }
        // 解释复用会话已有结论，不解析新对象：绑定当前事件只为保持会话连续性。
        bindCurrentIncident(task, context, "", ResourceTarget.unknown());
        explanations.run(task);
        appendReply(task);
    }

    /**
     * 状态查询：全局指标直接取数；实例/路由口径先落到真实对象上，点不出对象时才澄清。
     *
     * 「查什么」这一层也可能判不出来（「order-service 现在什么状态」没有指标/实例/路由口径词）。
     * 这时如果问题点名的对象能解析出来，就不是「问法不合法」而是「这句话更像一次故障追问」，
     * 改按故障调查执行；否则退回如实说明查询口径。
     */
    private void runQuery(InvestigationTask task, Session session, AgentContext context, IntentDecision decision,
                          AgentRequestOptions options) {
        QuerySubject subject = IntentClassifier.stateSubject(task.question());
        if (subject == QuerySubject.METRIC) {
            bindCurrentIncident(task, context, "", ResourceTarget.unknown());
            answerQuery(task, subject);
            return;
        }
        TargetResolution resolution = resolveTarget(task.question(), context, options.target());
        if (resolution.needsClarification()) {
            if (decision.targetHint().isBlank() && subject == QuerySubject.INSTANCE) {
                // 没点名任何对象：按注册表整体口径回答「一共几个实例」，这不缺目标。
                bindCurrentIncident(task, context, "", ResourceTarget.unknown());
                answerQuery(task, subject);
                return;
            }
            task.step(AgentStepType.TARGET_RESOLUTION, STEP_TARGET, StepStatus.COMPLETED, TARGET_NEEDS_INPUT);
            appendMessage(task.sessionId(), MessageRole.AGENT, resolution.clarification(), task.taskId());
            task.waitForInput(resolution.clarification());
            return;
        }
        if (subject == QuerySubject.NONE && hasTargetClue(decision, options)
                && escalateToInvestigation(task, session, context, decision, resolution, options,
                        QUERY_WITHOUT_SUBJECT)) {
            return;
        }
        task.step(AgentStepType.TARGET_RESOLUTION, STEP_TARGET, StepStatus.COMPLETED,
                "已确定查询对象：" + describe(resolution.target()));
        bindResolvedTarget(task, context, resolution.investigationPath(), resolution.target());
        answerQuery(task, subject);
    }

    private void answerQuery(InvestigationTask task, QuerySubject subject) {
        queries.run(task, subject);
        appendReply(task);
    }

    /** 处置请求：尽力解析对象，解析不出也照样出计划（计划才是产物），绝不因为对象不明而澄清。 */
    private void runActionPlan(InvestigationTask task, AgentContext context, IntentDecision decision,
                               AgentRequestOptions options) {
        task.step(AgentStepType.TARGET_RESOLUTION, STEP_TARGET, StepStatus.RUNNING, TARGET_RUNNING);
        TargetResolution resolution = resolveTarget(task.question(), context, options.target());
        if (resolution.needsClarification()) {
            task.step(AgentStepType.TARGET_RESOLUTION, STEP_TARGET, StepStatus.COMPLETED,
                    "未能解析出确定对象，处置计划将按用户描述生成，供人工核对后执行");
            bindCurrentIncident(task, context, "", ResourceTarget.unknown());
        } else {
            task.step(AgentStepType.TARGET_RESOLUTION, STEP_TARGET, StepStatus.COMPLETED,
                    "已确定处置对象：" + describe(resolution.target()));
            bindResolvedTarget(task, context, resolution.investigationPath(), resolution.target());
        }
        actionPlans.run(task, decision);
        appendReply(task);
    }

    /** 故障调查：目标解析不出来就问用户。 */
    private void runInvestigation(InvestigationTask task, Session session, AgentContext context,
                                  IntentDecision decision, AgentRequestOptions options) {
        task.step(AgentStepType.TARGET_RESOLUTION, STEP_TARGET, StepStatus.RUNNING, TARGET_RUNNING);
        TargetResolution resolution = resolveTarget(task.question(), context, options.target());
        if (resolution.needsClarification()) {
            task.step(AgentStepType.TARGET_RESOLUTION, STEP_TARGET, StepStatus.COMPLETED, TARGET_NEEDS_INPUT);
            appendMessage(task.sessionId(), MessageRole.AGENT, resolution.clarification(), task.taskId());
            task.waitForInput(resolution.clarification());
            return;
        }
        startInvestigation(task, session, context, resolution, options);
    }

    /**
     * 意图识别不出的处理：问题里能解析出对象就按故障调查继续（步骤与意图里写明依据），
     * 找不到对象就回一段自我介绍与能力清单——闲聊不该被追问「请给出请求路径」。
     *
     * 没有对象线索时不跑目标解析：这类输入（「你好」「谢谢」）既不需要读注册数据，
     * 也不能沿用会话当前对象，否则一句客套话会莫名其妙地重启上一轮调查。
     */
    private void runUnknown(InvestigationTask task, Session session, AgentContext context,
                            IntentDecision decision, AgentRequestOptions options) {
        if (decision.targetHint().isBlank() && !hasExplicitTarget(options)) {
            explainIdentity(task, context, decision);
            return;
        }
        task.step(AgentStepType.TARGET_RESOLUTION, STEP_TARGET, StepStatus.RUNNING, TARGET_RUNNING);
        TargetResolution resolution = resolveTarget(task.question(), context, options.target());
        if (resolution.needsClarification()) {
            explainIdentity(task, context, decision);
            return;
        }
        task.classify(TaskType.INVESTIGATION,
                decision.as(AgentIntent.INVESTIGATE, Confidence.MEDIUM, UNKNOWN_TARGET_FALLBACK));
        startInvestigation(task, session, context, resolution, options);
    }

    /** 兜底说明：沿用当前事件（若有）但不新建事件，直接讲清身份、能力与提问方式。 */
    private void explainIdentity(InvestigationTask task, AgentContext context, IntentDecision decision) {
        task.step(AgentStepType.TARGET_RESOLUTION, STEP_TARGET, StepStatus.COMPLETED, UNKNOWN_INTRO_STEP);
        task.classify(TaskType.EXPLAIN,
                decision.as(AgentIntent.EXPLAIN, Confidence.MEDIUM, UNKNOWN_INTRO_FALLBACK));
        bindCurrentIncident(task, context, "", ResourceTarget.unknown());
        explanations.introduce(task);
        appendReply(task);
    }

    /**
     * 证据驱动的路径纠正：轻量路径给不出有用结果时，改按故障调查执行一次。
     *
     * 前提是问题真的点了对象（{@link #hasTargetClue}），先解析一次；解析不出对象就不纠正，
     * 按原路径如实说明才是诚实的——纠正的意义在于「有具体对象可查」，不是把每个问题都变成调查。
     *
     * @return 是否已改走调查（{@code false} 表示调用方应保持原路径）
     */
    private boolean escalateToInvestigation(InvestigationTask task, Session session, AgentContext context,
                                            IntentDecision decision, AgentRequestOptions options, String reason) {
        return escalateToInvestigation(task, session, context, decision,
                resolveTarget(task.question(), context, options.target()), options, reason);
    }

    /** 已经解析过对象的纠正：纠正要改写任务类型与意图，并单独记一步「路径纠正」，避免形态与意图对不上。 */
    private boolean escalateToInvestigation(InvestigationTask task, Session session, AgentContext context,
                                            IntentDecision decision, TargetResolution resolution,
                                            AgentRequestOptions options, String reason) {
        if (resolution.needsClarification()) {
            return false;
        }
        String correction = reason + CORRECTION_SUFFIX;
        task.classify(TaskType.INVESTIGATION,
                decision.as(AgentIntent.INVESTIGATE, Confidence.MEDIUM, correction));
        task.step(AgentStepType.INTENT_RESOLUTION, STEP_CORRECTION, StepStatus.COMPLETED,
                correction + "（对象：" + describe(resolution.target()) + "）");
        startInvestigation(task, session, context, resolution, options);
        return true;
    }

    /**
     * 问题里是否点了对象：文本线索或调用方显式指定的目标。
     *
     * 只认「用户自己说出来的对象」：会话里沿用来的当前对象不算——「现在有几个」这种缺少宾语的问法
     * 该如实回答「口径不明」，而不是拿上一轮的对象重启一次调查。
     */
    private static boolean hasTargetClue(IntentDecision decision, AgentRequestOptions options) {
        return (decision != null && !decision.targetHint().isBlank()) || hasExplicitTarget(options);
    }

    /** 目标确定后的调查启动：选事件、挂任务、写开始消息、交给调查图。 */
    private void startInvestigation(InvestigationTask task, Session session, AgentContext context,
                                    TargetResolution resolution, AgentRequestOptions options) {
        IncidentChoice choice = pickIncident(session, context, resolution.target(), options.timeRange());
        registry.attachTask(choice.incident().incidentId(), task.taskId());
        task.bind(choice.incident().incidentId(), resolution.investigationPath(), resolution.target());
        task.step(AgentStepType.TARGET_RESOLUTION, STEP_TARGET, StepStatus.COMPLETED,
                "已确定调查对象：" + describe(resolution.target()));
        appendMessage(task.sessionId(), MessageRole.AGENT, started(choice, resolution.target()), task.taskId());
        investigations.run(task, report -> appendConclusion(task, report));
    }

    /**
     * 调查结论落成一条 Agent 回复：模型解读优先，没有解读时用规则结论。
     *
     * 「已继续调查…」只是受理播报，不该占着答案的位置；结论自己落一条回复，气泡里才是那段话。
     */
    private void appendConclusion(InvestigationTask task, InvestigationReport report) {
        String analysis = report.aiAnalysis();
        String answer = analysis == null || analysis.isBlank() ? report.summary() : analysis;
        if (answer.isBlank()) {
            return;
        }
        appendMessage(task.sessionId(), MessageRole.AGENT, answer, task.taskId());
    }

    /** 已识别但未开放的能力：如实回复边界，不进入目标解析，也不跑一轮空调查。 */
    private void unsupported(InvestigationTask task, IntentDecision decision) {
        String reply = decision.intent() == AgentIntent.CREATE_INSPECTION
                ? UNSUPPORTED_INSPECTION : UNSUPPORTED_KNOWLEDGE;
        task.step(AgentStepType.ANSWER, STEP_ANSWER, StepStatus.COMPLETED, reply);
        task.complete(new InvestigationReport(reply, Confidence.HIGH, List.of(), List.of(UNSUPPORTED_NOTE),
                List.of(), null));
        appendMessage(task.sessionId(), MessageRole.AGENT, reply, task.taskId());
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
     * 轻量任务的绑定：只沿用当前事件（若有），不新建事件、不挂任务、不覆盖事件结论。
     *
     * 状态查询与解释不产出调查结论，把任务挂到事件上会把事件状态卡在「调查中」、
     * 也会让解释覆盖掉真正的调查结论；而挂在同一事件下，会话的追问与解释才连得上。
     */
    private void bindCurrentIncident(InvestigationTask task, AgentContext context, String path,
                                     ResourceTarget target) {
        if (context == null || !context.hasActiveIncident()) {
            return;
        }
        task.bind(context.activeIncidentId(), path, target);
    }

    /**
     * 已解析对象的绑定：有活动事件时挂在事件下（保持追问连续），没有事件时也要把对象落到任务上。
     *
     * 轻量任务不新建事件，但解析出的对象是本次取数与预检的口径——不绑就会退回全局口径，
     * 用户问的是某个服务，回答却在报注册表总数。
     */
    private void bindResolvedTarget(InvestigationTask task, AgentContext context, String path,
                                    ResourceTarget target) {
        String incidentId = context != null && context.hasActiveIncident() ? context.activeIncidentId() : null;
        task.bind(incidentId, path, target);
    }

    /** 结论落成一条 Agent 回复：任务失败或没有结论时静默跳过，不伪造回复。 */
    private void appendReply(InvestigationTask task) {
        InvestigationReport report = task.view().result();
        if (report == null || report.summary().isBlank()) {
            return;
        }
        appendMessage(task.sessionId(), MessageRole.AGENT, report.summary(), task.taskId());
    }

    /** 意图到执行形态的映射：UNKNOWN 先按调查形态走，能解析出对象就继续调查，否则改为自我介绍与能力清单。 */
    private static TaskType typeOf(IntentDecision decision) {
        return switch (decision.intent()) {
            case QUERY_STATE -> TaskType.QUERY;
            case INVESTIGATE, UNKNOWN -> TaskType.INVESTIGATION;
            case EXPLAIN -> TaskType.EXPLAIN;
            case ACTION_REQUEST -> TaskType.ACTION_PLAN;
            case CREATE_INSPECTION, KNOWLEDGE_QUERY -> TaskType.UNSUPPORTED;
        };
    }

    /** 意图步骤的结果说明：意图、置信度、执行形态与判断依据一起给出，便于核对「为什么这么做」。 */
    private static String intentDetail(IntentDecision decision, TaskType type) {
        return "意图=" + decision.intent() + "（置信度 " + decision.confidence() + "）→ 执行方式=" + type
                + "：" + decision.reason();
    }

    /** 交给意图解释器的上下文摘要：只给当前对象与最近的用户消息，不夹带任何受控事实。 */
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

    /** 请求是否显式指定了对象（Workbench 的「高级上下文」会带显式目标，此时不能只认文本线索）。 */
    private static boolean hasExplicitTarget(AgentRequestOptions options) {
        ResourceTarget target = options == null ? null : options.target();
        return target != null && target.type() != TargetType.UNKNOWN && !target.value().isBlank();
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
            return new IncidentChoice(updated, false);
        }
        return new IncidentChoice(registry.openIncident(session.sessionId(), IncidentOrigin.USER, target, timeRange),
                true);
    }

    private static String started(IncidentChoice choice, ResourceTarget target) {
        String prefix = choice.created() ? "已开始调查" : "已继续调查";
        // 不在这里承诺具体步骤：计划由调查图按问题规划后随任务快照暴露。
        return prefix + describe(target) + "：按问题规划只读调查步骤。";
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