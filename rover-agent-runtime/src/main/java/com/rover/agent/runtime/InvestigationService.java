package com.rover.agent.runtime;

import com.rover.agent.core.model.Incident;
import com.rover.agent.core.model.IncidentOrigin;
import com.rover.agent.core.model.InvestigationReport;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.StepStatus;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.port.InstanceReadPort;
import com.rover.agent.core.port.MetricReadPort;
import com.rover.agent.core.port.RouteReadPort;
import com.rover.agent.core.port.TraceReadPort;
import com.rover.agent.runtime.graph.InvestigationGraph;
import com.rover.agent.runtime.graph.InvestigationOutcome;
import com.rover.agent.runtime.llm.ModelExplainer;
import com.rover.agent.runtime.task.IncidentRegistry;
import com.rover.agent.runtime.task.InvestigationTask;
import com.rover.agent.runtime.task.InvestigationTaskRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 只读故障调查用例入口：接收问题、开启会话与事件、登记并异步执行调查任务。
 *
 * 调查事实全部来自 {@code com.rover.agent.core.port} 的只读端口；模型只做解读，
 * 不可用时降级为规则诊断。任务状态只保留在当前进程，重启后不可查询。
 */
public final class InvestigationService {

    private static final Logger log = LoggerFactory.getLogger(InvestigationService.class);
    private static final int MAX_PATH_LENGTH = 512;
    private static final int MAX_QUESTION_LENGTH = 1000;

    private static final String STEP_AI = "AI 解读";
    private static final String AI_RUNNING = "根据只读快照与调查结论生成解释";
    private static final String AI_COMPLETED = "已生成解释";
    private static final String AI_FAILED = "模型暂时不可用，规则诊断已保留";
    private static final String AI_UNAVAILABLE = "模型暂时不可用，当前展示规则诊断。";
    private static final String AI_NOT_CONFIGURED = "尚未配置模型，当前展示规则诊断。";

    private final RouteReadPort routes;
    private final InstanceReadPort instances;
    private final MetricReadPort metrics;
    private final TraceReadPort traces;
    private final IncidentRegistry incidents;
    private final InvestigationTaskRegistry tasks;
    private final ModelExplainer explainer;

    public InvestigationService(RouteReadPort routes, InstanceReadPort instances, MetricReadPort metrics,
                                TraceReadPort traces, IncidentRegistry incidents, InvestigationTaskRegistry tasks,
                                ModelExplainer explainer) {
        this.routes = routes;
        this.instances = instances;
        this.metrics = metrics;
        this.traces = traces;
        this.incidents = incidents;
        this.tasks = tasks;
        this.explainer = explainer;
    }

    /** 提交一次主动调查：开启会话与事件，登记并异步执行任务。 */
    public TaskView submit(String path, String question) {
        String target = validatePath(path);
        String asked = validateQuestion(question);
        Session session = incidents.openSession();
        Incident incident = incidents.openIncident(session.sessionId(), IncidentOrigin.USER, target);
        InvestigationTask task = registerTask(session, incident, target, asked);
        incidents.attachTask(incident.incidentId(), task.taskId());
        try {
            tasks.execute(() -> run(task));
        } catch (RejectedExecutionException ex) {
            tasks.discard(task.taskId());
            rollback(incident, session);
            throw ex;
        }
        return task.view();
    }

    /** 登记任务；容量已满被拒时回滚本次会话与事件，避免留下没有任务的孤立记录。 */
    private InvestigationTask registerTask(Session session, Incident incident, String target, String asked) {
        try {
            return tasks.register(session.sessionId(), incident.incidentId(), target, asked);
        } catch (RejectedExecutionException ex) {
            rollback(incident, session);
            throw ex;
        }
    }

    private void rollback(Incident incident, Session session) {
        incidents.removeIncident(incident.incidentId());
        incidents.removeSession(session.sessionId());
    }

    public TaskView get(String taskId) {
        return tasks.get(taskId);
    }

    private void run(InvestigationTask task) {
        task.start();
        try {
            InvestigationGraph graph = new InvestigationGraph(routes, instances, metrics, traces, task::step);
            InvestigationOutcome outcome = graph.investigate(task.path());
            InvestigationReport report = new InvestigationReport(
                    outcome.findings().summary(), outcome.findings().confidence(), outcome.evidence(),
                    outcome.limitations(), outcome.findings().hypotheses(), null);
            report = explain(task, report, outcome);
            task.complete(report);
        } catch (Exception ex) {
            log.error("Agent 诊断任务异常", ex);
            task.fail("诊断任务执行失败");
        }
    }

    private InvestigationReport explain(InvestigationTask task, InvestigationReport report,
                                        InvestigationOutcome outcome) {
        if (!explainer.available()) {
            return withoutAnalysis(report, AI_NOT_CONFIGURED);
        }
        try {
            task.step(STEP_AI, StepStatus.RUNNING, AI_RUNNING);
            String analysis = explainer.explain(task.path(), task.question(), outcome.evidence(),
                    outcome.findings().hypotheses());
            task.step(STEP_AI, StepStatus.COMPLETED, AI_COMPLETED);
            return new InvestigationReport(report.summary(), report.confidence(), report.evidence(),
                    report.limitations(), report.hypotheses(), analysis.trim());
        } catch (Exception ex) {
            log.warn("Agent 模型解读失败", ex);
            task.step(STEP_AI, StepStatus.FAILED, AI_FAILED);
            return withoutAnalysis(report, AI_UNAVAILABLE);
        }
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