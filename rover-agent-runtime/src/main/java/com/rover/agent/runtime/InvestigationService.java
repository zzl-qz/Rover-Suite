package com.rover.agent.runtime;

import com.rover.agent.core.capability.CapabilityExecutor;
import com.rover.agent.core.capability.CapabilityRegistry;
import com.rover.agent.core.event.TaskEventSubscriber;
import com.rover.agent.core.event.TaskEventSubscription;
import com.rover.agent.core.model.AgentStepType;
import com.rover.agent.core.model.Incident;
import com.rover.agent.core.model.IncidentOrigin;
import com.rover.agent.core.model.InvestigationReport;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.StepStatus;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.model.TimeRange;
import com.rover.agent.core.planning.InvestigationPlanner;
import com.rover.agent.core.planning.PlanValidator;
import com.rover.agent.core.planning.PlanningLimits;
import com.rover.agent.core.planning.RuleBasedPlanner;
import com.rover.agent.core.port.ConfigReadPort;
import com.rover.agent.core.port.EventReadPort;
import com.rover.agent.core.port.InstanceReadPort;
import com.rover.agent.core.port.KnowledgeReadPort;
import com.rover.agent.core.port.LogQueryPort;
import com.rover.agent.core.port.MetricReadPort;
import com.rover.agent.core.port.RouteReadPort;
import com.rover.agent.core.port.TraceReadPort;
import com.rover.agent.runtime.graph.DynamicInvestigationGraph;
import com.rover.agent.runtime.journal.OpsJournal;
import com.rover.agent.runtime.graph.InvestigationOutcome;
import com.rover.agent.runtime.llm.ModelExplainer;
import com.rover.agent.runtime.task.IncidentRegistry;
import com.rover.agent.runtime.task.InvestigationTask;
import com.rover.agent.runtime.task.InvestigationTaskRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 只读故障调查用例入口：登记调查任务并执行「调查图 + 模型解读」的完整闭环。
 *
 * 提交与执行是分开的：{@link #register} 只登记 PENDING 任务（不阻塞调用线程），
 * {@link #run} 在 Agent Worker 线程里执行调查。目标解析（把自然语言问题落到调查对象）属于编排层，
 * 由编排层解析完成后调用 {@link #run}。
 *
 * 调查事实全部来自 {@code com.rover.agent.core.port} 的只读端口；模型只做解读，
 * 不可用时降级为规则诊断。任务状态只保留在当前进程，重启后不可查询。
 */
public final class InvestigationService {

    private static final Logger log = LoggerFactory.getLogger(InvestigationService.class);
    private static final int MAX_PATH_LENGTH = 512;
    private static final int MAX_QUESTION_LENGTH = 1000;

    /** 默认能力注册表：纯数据且不可变，默认装配与规划器共用同一份，避免口径分叉。 */
    private static final CapabilityRegistry STANDARD_CAPABILITIES = CapabilityRegistry.standard();

    private static final String STEP_AI = "AI 解读";
    private static final String AI_RUNNING = "根据只读快照与调查结论生成解释";
    private static final String AI_COMPLETED = "已生成解释";
    private static final String AI_FAILED = "模型暂时不可用，规则诊断已保留";
    private static final String AI_UNAVAILABLE = "模型暂时不可用，当前展示规则诊断。";
    private static final String AI_NOT_CONFIGURED = "尚未配置模型，当前展示规则诊断。";

    private final IncidentRegistry incidents;
    private final InvestigationTaskRegistry tasks;
    private final ModelExplainer explainer;
    private final InvestigationPlanner planner;
    private final PlanValidator validator;
    private final CapabilityExecutor executor;
    private final PlanningLimits limits;
    private final OpsJournal journal;

    /**
     * 默认装配：确定性规划器 + 标准能力注册表 + 默认规划上限。
     *
     * 未显式注入规划组件的调用方（旧入口与既有测试）走这里，行为与固定调查链一致：
     * 计划顺序仍是路由 → 实例 → 指标 → 追踪，只是改由动态图按同样的边界执行。
     */
    public InvestigationService(RouteReadPort routes, InstanceReadPort instances, MetricReadPort metrics,
                                TraceReadPort traces, ConfigReadPort configs, EventReadPort events,
                                LogQueryPort logs, KnowledgeReadPort knowledge,
                                IncidentRegistry incidents, InvestigationTaskRegistry tasks,
                                ModelExplainer explainer) {
        this(incidents, tasks, explainer,
                new RuleBasedPlanner(STANDARD_CAPABILITIES),
                new PlanValidator(STANDARD_CAPABILITIES, PlanningLimits.defaults()),
                new CapabilityExecutor(routes, instances, metrics, traces, configs, events, logs, knowledge,
                        STANDARD_CAPABILITIES),
                PlanningLimits.defaults(), OpsJournal.none());
    }

    /**
     * 完整装配：由配置层注入规划器（可含模型建议）、计划校验器、能力执行器与规划上限。
     *
     * 只读端口不在这里出现：取数统一经 {@link CapabilityExecutor}，本服务不再各自持有端口，
     * 避免出现「图走执行器、服务又直连端口」的两套取数口径。
     */
    public InvestigationService(IncidentRegistry incidents, InvestigationTaskRegistry tasks,
                                ModelExplainer explainer, InvestigationPlanner planner, PlanValidator validator,
                                CapabilityExecutor executor, PlanningLimits limits) {
        this(incidents, tasks, explainer, planner, validator, executor, limits, OpsJournal.none());
    }

    /** 完整装配。调查结束后，有已确认根因才写入资源笔记。 */
    public InvestigationService(IncidentRegistry incidents, InvestigationTaskRegistry tasks,
                                ModelExplainer explainer, InvestigationPlanner planner, PlanValidator validator,
                                CapabilityExecutor executor, PlanningLimits limits, OpsJournal journal) {
        this.incidents = incidents;
        this.tasks = tasks;
        this.explainer = explainer;
        this.planner = planner;
        this.validator = validator;
        this.executor = executor;
        this.limits = limits == null ? PlanningLimits.defaults() : limits;
        this.journal = journal == null ? OpsJournal.none() : journal;
    }

    /** 一次性调查入口：新建会话与事件后提交一次「目标已确定」的调查，不需要连续追问时用它最省事。 */
    public TaskView submit(String path, String question) {
        return submit(path, question, IncidentOrigin.USER, null);
    }

    /**
     * 事件接入入口：告警 / 网关切面异常等事件触发一次自动调查。
     *
     * 与人工 {@link #submit(String, String)} 的差别只有两点：事件来源记为 {@link IncidentOrigin#ALERT}，
     * 且事件自带一个观测窗口（不传则按默认窗口）。会话按事件独立开（userId 为 null），
     * 因此不会与某个人工会话的「单活跃任务」锁冲突（不会 409）。
     */
    public TaskView submitAlert(String path, String alertMessage, TimeRange timeRange) {
        String question = "告警自动调查："
                + (alertMessage == null || alertMessage.isBlank() ? "请根据下方路径与窗口排查失败原因" : alertMessage);
        return submit(path, question, IncidentOrigin.ALERT, timeRange);
    }

    private TaskView submit(String path, String question, IncidentOrigin origin, TimeRange timeRange) {
        String target = validatePath(path);
        String asked = validateQuestion(question);
        Session session = incidents.openSession();
        Incident incident = timeRange == null
                ? incidents.openIncident(session.sessionId(), origin, ResourceTarget.route(target))
                : incidents.openIncident(session.sessionId(), origin, ResourceTarget.route(target), timeRange);
        try {
            InvestigationTask task = register(session.sessionId(), asked);
            task.bind(incident.incidentId(), target, ResourceTarget.route(target));
            incidents.attachTask(incident.incidentId(), task.taskId());
            tasks.execute(() -> run(task));
            return task.view();
        } catch (RejectedExecutionException ex) {
            rollback(incident, session);
            throw ex;
        }
    }

    /**
     * 登记一个待执行的调查任务：只落 PENDING 快照，不解析目标、不发起任何阻塞调用。
     *
     * @throws com.rover.agent.runtime.task.SessionTaskRunningException 同会话已有仍在执行的任务
     * @throws RejectedExecutionException                                任务登记容量已满
     */
    public InvestigationTask register(String sessionId, String question) {
        return tasks.register(sessionId, question);
    }

    /** 提交执行；队列满时抛 {@link RejectedExecutionException}，调用方应回滚登记并回 429。 */
    public void execute(Runnable runnable) {
        tasks.execute(runnable);
    }

    /** 提交执行失败时回滚登记，避免留下永远 PENDING 的任务。 */
    public void discard(String taskId) {
        tasks.discard(taskId);
    }

    /** 执行一次已解析目标的调查；不回调结论，行为与旧入口一致。 */
    public void run(InvestigationTask task) {
        run(task, null);
    }

    /**
     * 执行一次已解析目标的调查：调查图 → 规则结论 → 模型解读 → 结论回写事件。
     *
     * 调用前必须已通过 {@code task.bind(...)} 绑定事件、取数路径与结构化目标。
     * 本方法只在 Agent Worker 线程里调用，绝不占用 Servlet 请求线程。
     *
     * {@code onReport} 在任务定型之前回调：客户端收到 TASK_COMPLETED 就会重新拉取会话，
     * 结论若在此之后才落库，它会先看到一条只有「已继续调查…」的时间线。
     */
    public void run(InvestigationTask task, Consumer<InvestigationReport> onReport) {
        task.start();
        task.markRunning();
        try {
            // 执行前若已被取消（例如排队期间被取消），直接退出，不再发起任何取数。
            if (task.cancelled()) {
                return;
            }
            DynamicInvestigationGraph graph =
                    new DynamicInvestigationGraph(planner, validator, executor, limits, task);
            InvestigationOutcome outcome = graph.investigate(task.taskId(), task.path(), task.question(),
                    task.target(), task.intent());
            if (task.cancelled()) {
                return;
            }
            if (outcome.needsClarification()) {
                // 规划认为继续调查缺少必要信息：停在澄清点，而不是硬凑一个没有依据的结论。
                task.waitForInput(outcome.clarification());
                return;
            }
            InvestigationReport report = new InvestigationReport(
                    outcome.findings().summary(), outcome.findings().confidence(), outcome.evidence(),
                    outcome.limitations(), outcome.findings().hypotheses(), null);
            report = explain(task, report, outcome);
            if (task.cancelled()) {
                return;
            }
            publish(onReport, report);
            task.complete(report);
            // 结论回写到事件：事件因此成为「一个问题的多次调查」的聚合点，追问时能继承最新结论。
            incidents.summarise(task.incidentId(), report.summary());
            journal.record(task.view());
        } catch (Exception ex) {
            if (task.cancelled()) {
                // 取消过程中断：结论已被标记 CANCELLED，不要覆盖成失败。
                return;
            }
            log.error("Agent 诊断任务异常", ex);
            task.fail("诊断任务执行失败");
        } finally {
            task.clearRunning();
        }
    }

    /** 取消一个仍在执行中的调查任务；任务不存在或已终态时返回 false。 */
    public boolean cancel(String taskId) {
        return tasks.cancel(taskId);
    }

    /** 结论回调失败只损失可追溯性，不该把一次已经跑完的调查判成失败。 */
    private static void publish(Consumer<InvestigationReport> onReport, InvestigationReport report) {
        if (onReport == null) {
            return;
        }
        try {
            onReport.accept(report);
        } catch (RuntimeException ex) {
            log.warn("调查结论落库失败，不影响本次调查结果", ex);
        }
    }

    /** 撤销未成功提交的调查所留下的会话与事件。 */
    private void rollback(Incident incident, Session session) {
        incidents.removeIncident(incident.incidentId());
        incidents.removeSession(session.sessionId());
    }

    public TaskView get(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * 订阅某任务的事件流（供 SSE 展示）；任务不在执行登记表里时返回空，由调用方回 404。
     *
     * 订阅成功后先补发一份全量快照（状态 + 已产生的解读文本），再按增量投递；
     * 因此晚连上、掉队重连的客户端都不会看到空白或重复内容。事件写往订阅者队列是非阻塞的，
     * 客户端断开只影响它自己这条订阅。
     */
    public Optional<TaskEventSubscription> subscribeEvents(String taskId, TaskEventSubscriber subscriber) {
        InvestigationTask task = tasks.find(taskId);
        return task == null ? Optional.empty() : Optional.of(task.subscribe(subscriber));
    }

    private InvestigationReport explain(InvestigationTask task, InvestigationReport report,
                                        InvestigationOutcome outcome) {
        if (!explainer.configured()) {
            return withoutAnalysis(report, AI_NOT_CONFIGURED);
        }
        if (!explainer.available()) {
            return withoutAnalysis(report, AI_UNAVAILABLE);
        }
        try {
            task.step(AgentStepType.AI_EXPLANATION, STEP_AI, StepStatus.RUNNING, AI_RUNNING);
            ModelExplainer.Explanation explanation = explainer.explainStreaming(task.path(), task.question(),
                    outcome.evidence(), outcome.findings().hypotheses(), task::appendAnalysis, task::appendThinking);
            // 步骤里区分「运行时预读」与「模型另调」：解释的依据可追溯到具体快照，而不是一句「已生成解释」。
            task.step(AgentStepType.AI_EXPLANATION, STEP_AI, StepStatus.COMPLETED, completedDetail(explanation));
            return new InvestigationReport(report.summary(), report.confidence(), report.evidence(),
                    report.limitations(), report.hypotheses(), explanation.text().trim());
        } catch (Exception ex) {
            log.warn("Agent 模型解读失败", ex);
            task.step(AgentStepType.AI_EXPLANATION, STEP_AI, StepStatus.FAILED, AI_FAILED);
            return withoutAnalysis(report, AI_UNAVAILABLE);
        }
    }

    /** 解读成功的结果说明：如实区分运行时预读的快照与模型自己额外调用的工具。 */
    private static String completedDetail(ModelExplainer.Explanation explanation) {
        StringBuilder detail = new StringBuilder(AI_COMPLETED)
                .append("，运行时预读快照：").append(String.join("、", explanation.prefetched()));
        if (!explanation.tools().isEmpty()) {
            detail.append("；模型另调工具：").append(String.join("、", explanation.tools()));
        }
        return detail.toString();
    }

    private static InvestigationReport withoutAnalysis(InvestigationReport report, String limitation) {
        List<String> limitations = new ArrayList<>(report.limitations());
        limitations.add(limitation);
        return new InvestigationReport(report.summary(), report.confidence(), report.evidence(),
                List.copyOf(limitations), report.hypotheses(), null);
    }

    private static String validatePath(String path) {
        String target = path == null ? null : path.trim();
        if (target == null || target.isBlank() || target.length() > MAX_PATH_LENGTH || !target.startsWith("/")
                || target.startsWith("//") || target.indexOf('?') >= 0 || target.indexOf('#') >= 0
                || target.chars().anyMatch(ch -> Character.isWhitespace(ch) || Character.isISOControl(ch))) {
            throw new IllegalArgumentException("请输入不含查询参数的请求路径");
        }
        return target;
    }

    private static String validateQuestion(String question) {
        if (question == null || question.isBlank() || question.length() > MAX_QUESTION_LENGTH) {
            throw new IllegalArgumentException("请输入不超过 1000 字的问题");
        }
        return question.trim();
    }
}