package com.rover.agent.runtime;

import com.rover.agent.core.model.AgentStepType;
import com.rover.agent.core.model.Incident;
import com.rover.agent.core.model.IncidentOrigin;
import com.rover.agent.core.model.InvestigationReport;
import com.rover.agent.core.model.ResourceTarget;
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
import com.rover.agent.runtime.task.AnalysisStreamListener;
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
        Incident incident = incidents.openIncident(session.sessionId(), IncidentOrigin.USER,
                ResourceTarget.route(target));
        try {
            return start(session.sessionId(), incident, target, ResourceTarget.route(target), asked);
        } catch (RejectedExecutionException ex) {
            rollback(incident, session);
            throw ex;
        }
    }

    /**
     * 在已有会话与事件下启动一次调查：登记任务、挂到事件上、提交执行。
     *
     * 这是「启动一次调查」的唯一入口——会话与事件由调用方（编排层）负责解析与创建，
     * 本类只负责调查任务的登记、执行与结论产出。登记或提交被拒时抛
     * {@link RejectedExecutionException}，此时不会留下未执行的任务，事件与会话的清理由调用方决定。
     */
    public TaskView start(String sessionId, Incident incident, String path, ResourceTarget target, String question) {
        InvestigationTask task = tasks.register(sessionId, incident.incidentId(), path, target, question);
        incidents.attachTask(incident.incidentId(), task.taskId());
        try {
            tasks.execute(() -> run(task));
        } catch (RejectedExecutionException ex) {
            tasks.discard(task.taskId());
            throw ex;
        }
        return task.view();
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
     * 订阅某任务的「AI 解读」增量（供 SSE 实时展示）；任务不存在时返回 false，由调用方回 404。
     * 已完成解读的任务会先补发全文再立即结束，因此晚连上的客户端不会看到空白。
     */
    public boolean subscribeAnalysis(String taskId, AnalysisStreamListener listener) {
        InvestigationTask task = tasks.find(taskId);
        if (task == null) {
            return false;
        }
        task.subscribeAnalysis(listener);
        return true;
    }

    /** 退订解读增量：客户端断开、超时或出错时调用。 */
    public void unsubscribeAnalysis(String taskId, AnalysisStreamListener listener) {
        InvestigationTask task = tasks.find(taskId);
        if (task != null) {
            task.unsubscribeAnalysis(listener);
        }
    }

    private void run(InvestigationTask task) {
        task.start();
        try {
            InvestigationGraph graph = new InvestigationGraph(routes, instances, metrics, traces, task::step);
            InvestigationOutcome outcome = graph.investigate(task.taskId(), task.path());
            InvestigationReport report = new InvestigationReport(
                    outcome.findings().summary(), outcome.findings().confidence(), outcome.evidence(),
                    outcome.limitations(), outcome.findings().hypotheses(), null);
            report = explain(task, report, outcome);
            task.complete(report);
            // 结论回写到事件：事件因此成为「一个问题的多次调查」的聚合点，追问时能继承最新结论。
            incidents.summarise(task.incidentId(), report.summary());
        } catch (Exception ex) {
            log.error("Agent 诊断任务异常", ex);
            task.fail("诊断任务执行失败");
        } finally {
            // 关闭解读流放在任务进入终态之后：订阅者收到结束通知时回读任务，一定拿到最终结论。
            task.closeAnalysis();
        }
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
                    outcome.evidence(), outcome.findings().hypotheses(), task::appendAnalysis);
            // 步骤里带上模型实际读过的工具：解释的依据可追溯到具体快照，而不是一句「已生成解释」。
            task.step(AgentStepType.AI_EXPLANATION, STEP_AI, StepStatus.COMPLETED, completedDetail(explanation.tools()));
            return new InvestigationReport(report.summary(), report.confidence(), report.evidence(),
                    report.limitations(), report.hypotheses(), explanation.text().trim());
        } catch (Exception ex) {
            log.warn("Agent 模型解读失败", ex);
            task.step(AgentStepType.AI_EXPLANATION, STEP_AI, StepStatus.FAILED, AI_FAILED);
            return withoutAnalysis(report, AI_UNAVAILABLE);
        }
    }

    /** 解读成功的结果说明：如实列出模型调用过的只读工具。 */
    private static String completedDetail(List<String> tools) {
        return tools.isEmpty() ? AI_COMPLETED : AI_COMPLETED + "，调用只读工具：" + String.join("、", tools);
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