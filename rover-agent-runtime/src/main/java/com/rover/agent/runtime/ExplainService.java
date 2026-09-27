package com.rover.agent.runtime;

import com.rover.agent.core.capability.CapabilityRegistry;
import com.rover.agent.core.model.AgentStepType;
import com.rover.agent.core.model.Confidence;
import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.Incident;
import com.rover.agent.core.model.IntentDecision;
import com.rover.agent.core.model.IntentTopic;
import com.rover.agent.core.model.InvestigationReport;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.StepStatus;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.repository.AgentTaskRepository;
import com.rover.agent.runtime.task.IncidentRegistry;
import com.rover.agent.runtime.task.InvestigationTask;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 解释用例：回答「你能做什么」与「这次结论是怎么来的 / 再说说」这类问题。
 *
 * 三类回答都不产生新事实，因此都不采集数据、不调用模型：
 * <ul>
 *   <li>能力咨询直接引用 {@link CapabilityRegistry#introduce()}——能力边界只有注册表一处声明，
 *       模型与提示词不会声称系统具备未实现的能力；</li>
 *   <li>结论解释复用会话里最近一次已完成的调查结论（含证据与判断边界），
 *       而不是为「为什么这么判断」再跑一轮调查；</li>
 *   <li>意图识别不出时的兜底同样引用注册表：先自我介绍并说明可以直接问什么，
 *       而不是向用户追问「请给出请求路径」。</li>
 * </ul>
 *
 * 没有任何可解释的结论时如实说明，并给出下一步建议；这与「查不到数据」是两种不同的边界，
 * 前者是会话还没有结论，后者是数据源不可用。
 */
public final class ExplainService {

    private static final Logger log = LoggerFactory.getLogger(ExplainService.class);

    private static final String STEP_ANSWER = "回答";

    private static final String CAPABILITY_NOTE = "能力清单来自代码注册表：未开放的能力不会被 Agent 调用。";

    private static final String NO_CONCLUSION =
            "当前会话还没有可解释的调查结论：先提出一个具体问题（例如某条路径为什么调用失败），我再基于那次结论展开说明。";
    private static final String NO_CONCLUSION_NOTE = "解释类问题只复用已有结论，不会为它单跑一轮调查。";

    private static final String CONCLUSION_PREFIX = "当前会话最近一次调查结论（";
    private static final String CONCLUSION_SEPARATOR = "）：";

    private static final int RECENT_TASK_SCAN = 5;

    private final CapabilityRegistry registry;
    private final IncidentRegistry incidents;
    private final AgentTaskRepository tasks;

    public ExplainService(CapabilityRegistry registry, IncidentRegistry incidents, AgentTaskRepository tasks) {
        this.registry = registry == null ? CapabilityRegistry.standard() : registry;
        this.incidents = incidents;
        this.tasks = tasks;
    }

    /** 执行一次解释：按意图主题决定讲能力还是讲结论。 */
    public void run(InvestigationTask task) {
        task.start();
        try {
            IntentDecision intent = task.intent();
            if (intent != null && intent.topic() == IntentTopic.CAPABILITIES) {
                answer(task, registry.introduce(), Confidence.HIGH, List.of(), List.of(CAPABILITY_NOTE));
                return;
            }
            Optional<TaskView> conclusion = latestConclusion(task.sessionId(), task.incidentId());
            if (conclusion.isEmpty()) {
                answer(task, NO_CONCLUSION, Confidence.LOW, List.of(), List.of(NO_CONCLUSION_NOTE));
                return;
            }
            TaskView view = conclusion.get();
            InvestigationReport report = view.result();
            String summary = CONCLUSION_PREFIX + view.question() + CONCLUSION_SEPARATOR + report.summary();
            answer(task, summary, report.confidence(), report.evidence(), report.limitations());
        } catch (Exception ex) {
            log.error("Agent 解释执行异常", ex);
            task.fail("解释执行失败");
        }
    }

    /**
     * 意图识别不出时的兜底说明：一句没听懂，再讲能做什么、可以怎么问。
     *
     * 闲聊型输入（「你好」「今天天气怎么样」）追问「请给出请求路径」是答非所问；
     * 但也不该甩一整份能力清单过来——这里用 {@link CapabilityRegistry#introduceBriefly()}，
     * 完整清单留给明确问「你能做什么」的人。置信度标 MEDIUM：这是兜底说明而不是对问题的回答。
     */
    public void introduce(InvestigationTask task) {
        task.start();
        try {
            answer(task, registry.introduceBriefly(), Confidence.MEDIUM, List.of(), List.of(CAPABILITY_NOTE));
        } catch (Exception ex) {
            log.error("Agent 兜底说明执行异常", ex);
            task.fail("兜底说明执行失败");
        }
    }

    /**
     * 会话里最近一次有结论的调查：先看当前事件下的任务（新的在前），再退回会话内最近任务。
     *
     * 只认已产出结论的任务：正在跑的调查还没有结论可解释，把它当成答案等于抢跑。
     */
    private Optional<TaskView> latestConclusion(String sessionId, String incidentId) {
        if (incidentId == null) {
            incidentId = incidents == null ? null
                    : incidents.session(sessionId).map(Session::activeIncidentId).orElse(null);
        }
        if (incidentId != null && incidents != null) {
            List<String> taskIds = incidents.incident(incidentId).map(Incident::taskIds).orElse(List.of());
            for (int i = taskIds.size() - 1; i >= 0; i--) {
                Optional<TaskView> view = tasks.find(taskIds.get(i)).filter(item -> item.result() != null);
                if (view.isPresent()) {
                    return view;
                }
            }
        }
        return tasks.recentBySession(sessionId, RECENT_TASK_SCAN).stream()
                .filter(view -> view.result() != null)
                .findFirst();
    }

    /**
     * 会话里是否有可解释的结论。
     *
     * 供编排层判断「解释这条路有没有东西可讲」：没有结论时不是解释得不好，而是还没有那次调查，
     * 编排层据此决定是否改走证据驱动的路径纠正。
     */
    public boolean hasConclusion(String sessionId) {
        return latestConclusion(sessionId, null).isPresent();
    }

    private static void answer(InvestigationTask task, String summary, Confidence confidence,
                               List<Evidence> evidence, List<String> limitations) {
        task.step(AgentStepType.ANSWER, STEP_ANSWER, StepStatus.COMPLETED, summary);
        task.complete(new InvestigationReport(summary, confidence, evidence, limitations, List.of(), null));
    }
}