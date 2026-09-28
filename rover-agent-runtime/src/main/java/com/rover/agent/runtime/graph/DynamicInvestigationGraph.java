package com.rover.agent.runtime.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.capability.CapabilityExecutor;
import com.rover.agent.core.capability.CapabilityResult;
import com.rover.agent.core.investigation.Findings;
import com.rover.agent.core.investigation.FindingsInput;
import com.rover.agent.core.investigation.InvestigationRules;
import com.rover.agent.core.model.AgentStepType;
import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.StepStatus;
import com.rover.agent.core.planning.InvestigationPlan;
import com.rover.agent.core.planning.InvestigationPlanner;
import com.rover.agent.core.planning.PlanProgress;
import com.rover.agent.core.planning.PlanValidator;
import com.rover.agent.core.planning.PlannedStep;
import com.rover.agent.core.planning.PlanningDecision;
import com.rover.agent.core.planning.PlanningLimits;
import com.rover.agent.core.planning.PlanningRequest;
import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import com.rover.agent.core.snapshot.RouteUpstreamSnapshot;
import com.rover.agent.core.snapshot.TraceSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 动态只读调查图：{@code PLAN → EXECUTE → EVALUATE →（继续规划 / 澄清 / 合成结论）}。
 *
 * 与固定调查链的区别只有一件事，但很关键：<b>下一步查什么由当前证据决定</b>。
 * 计划由 {@link InvestigationPlanner} 产出（模型可参与建议），每一步都要过 {@link PlanValidator}
 * 的只读与目标边界校验，实际取数由 {@link CapabilityExecutor} 完成；轮数、能力调用次数与计划步数
 * 由 {@link PlanningLimits} 强制封顶——「模型负责理解与建议，代码负责权限、执行与限制」。
 *
 * 图状态里只放标量（任务、路径、轮数、调用次数、判定结果）。证据、结构化快照与结论都放在本实例
 * 字段上：框架会把 {@code invoke()} 的状态做序列化快照，记录内部的嵌套集合与枚举回读时会退化，
 * 这个约束与固定链路一致，因此每次调查仍然新建一个图实例。
 */
public final class DynamicInvestigationGraph {

    private static final Logger log = LoggerFactory.getLogger(DynamicInvestigationGraph.class);

    private static final String NODE_PLAN = "plan";
    private static final String NODE_EXECUTE = "execute";
    private static final String NODE_EVALUATE = "evaluate";
    private static final String NODE_CLARIFY = "clarify";
    private static final String NODE_SYNTHESIS = "synthesise";

    private static final String VERDICT_CONTINUE = "CONTINUE";
    private static final String VERDICT_CLARIFY = "CLARIFY";
    private static final String VERDICT_FINISH = "FINISH";

    private static final String STATE_TASK_ID = "taskId";
    private static final String STATE_PATH = "path";
    private static final String STATE_QUESTION = "question";
    private static final String STATE_ROUNDS = "rounds";
    private static final String STATE_TOOL_CALLS = "toolCalls";
    private static final String STATE_HAS_PLAN = "hasPlan";
    private static final String STATE_VERDICT = "verdict";

    private static final String STEP_PLANNING = "规划调查步骤";
    private static final String STEP_LIMIT = "规划上限";
    private static final String STEP_SYNTHESIS = "调查推理";

    private static final String LIMIT_REACHED = "调查已达到规划轮数或能力调用上限，停止继续采集。";
    private static final String TOOL_LIMIT_REACHED = "已达到能力调用次数上限，本轮剩余只读步骤不再执行。";
    private static final int MAX_DETAIL_LENGTH = 600;

    private final InvestigationPlanner planner;
    private final PlanValidator validator;
    private final CapabilityExecutor executor;
    private final PlanningLimits limits;
    private final InvestigationReporter reporter;
    private final CompiledGraph graph;

    private final List<Evidence> evidence = new ArrayList<>();
    private final List<String> limitations = new ArrayList<>();
    private final Set<AgentCapability> settled = new LinkedHashSet<>();
    private final List<AgentCapability> executedCapabilities = new ArrayList<>();

    private String taskId = "";
    private String path = "";
    private String question = "";
    private ResourceTarget target = ResourceTarget.unknown();
    private InvestigationPlan plan = InvestigationPlan.empty();
    private int rounds;
    private int toolCalls;
    private boolean routeQueried;
    private RouteSnapshot route;
    private boolean routeRead;
    private DiscoveryMode discovery = DiscoveryMode.UNKNOWN;
    private List<InstanceSnapshot> instances;
    private TraceSnapshot traces;
    private List<RouteUpstreamSnapshot> routeUpstreams;
    private Findings findings;
    private String clarification;

    public DynamicInvestigationGraph(InvestigationPlanner planner, PlanValidator validator,
                                     CapabilityExecutor executor, PlanningLimits limits,
                                     InvestigationReporter reporter) {
        this.planner = planner;
        this.validator = validator;
        this.executor = executor;
        this.limits = limits == null ? PlanningLimits.defaults() : limits;
        this.reporter = reporter;
        this.graph = compile();
    }

    /**
     * 执行一次动态只读调查。
     *
     * @param taskId   证据归属的任务
     * @param path     取数用的请求路径；为空表示没有可调查路径
     * @param question 用户问题（供规划提示词与计划目标使用）
     * @param target   本次请求的目标对象
     */
    public InvestigationOutcome investigate(String taskId, String path, String question, ResourceTarget target) {
        this.taskId = taskId == null ? "" : taskId;
        this.path = path == null ? "" : path.trim();
        this.question = question == null ? "" : question.trim();
        this.target = target == null ? ResourceTarget.unknown() : target;
        OverAllState state;
        try {
            Map<String, Object> input = new HashMap<>();
            input.put(STATE_TASK_ID, this.taskId);
            input.put(STATE_PATH, this.path);
            input.put(STATE_QUESTION, this.question);
            input.put(STATE_ROUNDS, 0);
            input.put(STATE_TOOL_CALLS, 0);
            input.put(STATE_HAS_PLAN, false);
            input.put(STATE_VERDICT, "");
            state = graph.invoke(input).orElseThrow(() -> new IllegalStateException("调查图未返回状态"));
        } catch (Exception ex) {
            throw new IllegalStateException("调查图执行失败", ex);
        }
        Findings produced = this.findings;
        if (produced == null && clarification == null) {
            throw new IllegalStateException("调查图未产出结论");
        }
        return new InvestigationOutcome(produced, List.copyOf(evidence), List.copyOf(limitations), plan,
                List.copyOf(executedCapabilities), rounds, toolCalls, clarification);
    }

    private CompiledGraph compile() {
        try {
            return new StateGraph(keyStrategies())
                    .addNode(NODE_PLAN, AsyncNodeAction.node_async(this::planNode))
                    .addNode(NODE_EXECUTE, AsyncNodeAction.node_async(this::executeNode))
                    .addNode(NODE_EVALUATE, AsyncNodeAction.node_async(this::evaluateNode))
                    .addNode(NODE_CLARIFY, AsyncNodeAction.node_async(this::clarifyNode))
                    .addNode(NODE_SYNTHESIS, AsyncNodeAction.node_async(this::synthesise))
                    .addEdge(StateGraph.START, NODE_PLAN)
                    .addConditionalEdges(NODE_PLAN, AsyncEdgeAction.edge_async(this::planBranch),
                            Map.of("execute", NODE_EXECUTE, "synthesise", NODE_SYNTHESIS))
                    .addEdge(NODE_EXECUTE, NODE_EVALUATE)
                    .addConditionalEdges(NODE_EVALUATE, AsyncEdgeAction.edge_async(this::verdictBranch),
                            Map.of(VERDICT_CONTINUE, NODE_PLAN, VERDICT_CLARIFY, NODE_CLARIFY,
                                    VERDICT_FINISH, NODE_SYNTHESIS))
                    .addEdge(NODE_CLARIFY, StateGraph.END)
                    .addEdge(NODE_SYNTHESIS, StateGraph.END)
                    .compile();
        } catch (GraphStateException ex) {
            throw new IllegalStateException("调查图构建失败", ex);
        }
    }

    private static KeyStrategyFactory keyStrategies() {
        return () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put(STATE_TASK_ID, new ReplaceStrategy());
            strategies.put(STATE_PATH, new ReplaceStrategy());
            strategies.put(STATE_QUESTION, new ReplaceStrategy());
            strategies.put(STATE_ROUNDS, new ReplaceStrategy());
            strategies.put(STATE_TOOL_CALLS, new ReplaceStrategy());
            strategies.put(STATE_HAS_PLAN, new ReplaceStrategy());
            strategies.put(STATE_VERDICT, new ReplaceStrategy());
            return strategies;
        };
    }

    /** 本轮没有可执行步骤时直接进入结论合成，不空转一轮执行。 */
    private String planBranch(OverAllState state) {
        return state.value(STATE_HAS_PLAN, false) ? "execute" : "synthesise";
    }

    private String verdictBranch(OverAllState state) {
        return state.value(STATE_VERDICT, VERDICT_FINISH);
    }

    /**
     * 规划节点：产出并校验本轮计划。
     *
     * 模型建议在这里被压缩成「注册表里已接入的只读能力」；越界建议静默丢弃（但计划里保留用户可见的
     * 步骤依据），保证任何情况下都不会出现无法执行或越权的步骤。
     */
    private Map<String, Object> planNode(OverAllState state) {
        int round = state.value(STATE_ROUNDS, 0) + 1;
        this.rounds = round;
        PlanningRequest request = planningRequest();
        // 先上报「正在规划」：这一步可能等模型给出候选步骤，沉默期正是用户觉得「没在思考」的地方。
        // 终态上报沿用同一个步骤名，{@code InvestigationTask} 会就地补上结束时刻与结果说明。
        if (reporter != null) {
            reporter.step(AgentStepType.PLANNING, STEP_PLANNING, StepStatus.RUNNING, planningRunning(round));
        }
        InvestigationPlan candidate;
        try {
            candidate = planner.plan(request);
        } catch (Exception ex) {
            log.warn("Agent 调查规划失败，改用空计划", ex);
            candidate = InvestigationPlan.empty();
            limitations.add("调查规划失败，本轮未能产出可执行的只读步骤。");
        }
        InvestigationPlan validated = validator.validate(candidate, request, settled);
        this.plan = validated;
        if (reporter != null) {
            reporter.reportPlan(validated);
            reporter.step(AgentStepType.PLANNING, STEP_PLANNING, StepStatus.COMPLETED,
                    validated.isEmpty() ? "第 " + round + " 轮没有可执行的只读步骤" : describePlan(round, validated));
        }
        return Map.of(STATE_ROUNDS, round, STATE_HAS_PLAN, !validated.isEmpty());
    }

    /**
     * 执行节点：按计划顺序执行本轮尚未处理的能力。
     *
     * 有些能力对本目标没有判定价值（例如路由未知、静态上游或非 Nameserver 时的实例数据），
     * 这类步骤按事实跳过并记录依据；跳过同样计入「已处理」，下一轮不会重复规划它。
     */
    private Map<String, Object> executeNode(OverAllState state) {
        for (PlannedStep step : plan.steps()) {
            AgentCapability capability = step.capability();
            if (settled.contains(capability)) {
                continue;
            }
            if (toolCalls >= limits.maxToolCalls()) {
                // 调用次数上限是硬边界：触顶后不再发起任何能力调用，如实记下这一步没有执行。
                limitations.add(TOOL_LIMIT_REACHED);
                if (reporter != null) {
                    reporter.step(AgentStepType.PLANNING, STEP_LIMIT, StepStatus.COMPLETED,
                            "已达到能力调用次数上限（" + limits.maxToolCalls() + " 次），未执行：" + capability);
                }
                break;
            }
            settled.add(capability);
            String skip = skipReason(capability);
            if (skip != null) {
                report(capability);
                if (reporter != null) {
                    reporter.step(stepType(capability), stepName(capability), StepStatus.COMPLETED, skip);
                }
                continue;
            }
            // 取数是真实的 HTTP 调用，先上报「正在查询」：等待期能看见进度，靠的就是这一步。
            // 结束上报沿用同一个步骤名，任务侧会就地补上耗时与结果说明，不会多出一条步骤。
            if (reporter != null) {
                reporter.step(stepType(capability), stepName(capability), StepStatus.RUNNING, runningText(capability));
            }
            CapabilityResult result = executor.execute(capability, step.target(), path, taskId, route);
            if (result.executed()) {
                toolCalls++;
            }
            applyFacts(capability, result);
            evidence.addAll(result.evidence());
            // 多个能力会给出同一条边界说明（例如全局指标与按上游观测共用 60 秒窗口口径、
            // 配置快照按组件各给一次）。同一句话重复出现只会让判断边界显得像噪声，这里保序去重。
            for (String limitation : result.limitations()) {
                if (!limitations.contains(limitation)) {
                    limitations.add(limitation);
                }
            }
            report(capability);
            if (reporter != null) {
                reporter.step(result.stepType(), result.stepName(),
                        result.executed() ? StepStatus.COMPLETED : StepStatus.FAILED,
                        result.executed() ? detailOf(result) : limitationOf(result));
            }
        }
        return Map.of(STATE_TOOL_CALLS, toolCalls);
    }

    /**
     * 评估节点：判定继续、澄清还是收尾。
     *
     * 判定来自 Planner 的确定性实现；这里再叠加轮数与调用次数上限——触到上限时如实记录一条判断边界，
     * 而不是悄悄继续调查或假装已经查完。
     */
    private Map<String, Object> evaluateNode(OverAllState state) {
        PlanningRequest request = planningRequest();
        PlanProgress progress = PlanProgress.of(rounds, toolCalls, settled, 0);
        PlanningDecision decision = planner.evaluate(request, progress);
        String verdict;
        if (decision.shouldClarify()) {
            this.clarification = decision.clarification();
            if (reporter != null) {
                reporter.step(AgentStepType.PLANNING, "需要补充信息", StepStatus.COMPLETED, decision.clarification());
            }
            verdict = VERDICT_CLARIFY;
        } else if (decision.shouldContinue() && rounds < limits.maxRounds() && toolCalls < limits.maxToolCalls()) {
            verdict = VERDICT_CONTINUE;
        } else {
            if (decision.shouldContinue()) {
                limitations.add(LIMIT_REACHED);
                if (reporter != null) {
                    reporter.step(AgentStepType.PLANNING, STEP_LIMIT, StepStatus.COMPLETED,
                            "已达到规划上限（轮数 " + rounds + "/" + limits.maxRounds() + "，能力调用 " + toolCalls
                                    + "/" + limits.maxToolCalls() + "）：" + decision.reason());
                }
            }
            verdict = VERDICT_FINISH;
        }
        return Map.of(STATE_VERDICT, verdict, STATE_TOOL_CALLS, toolCalls);
    }

    /** 澄清节点：规划认为缺少继续调查所需的信息，停在澄清点而不是硬凑一个结论。 */
    private Map<String, Object> clarifyNode(OverAllState state) {
        return Map.of();
    }

    /** 合成节点：只依据已采集的只读事实逐条确认或排除候选故障原因。 */
    private Map<String, Object> synthesise(OverAllState state) {
        // 判定本身是纯内存计算，但它是「从证据到结论」的那一步，值得在时间线上单独可见。
        if (reporter != null) {
            reporter.step(AgentStepType.DIAGNOSIS, STEP_SYNTHESIS, StepStatus.RUNNING,
                    "正在按已采集的证据逐条判定候选故障原因");
        }
        FindingsInput input = new FindingsInput(path, route, routeRead, discovery, instances, traces, routeUpstreams);
        Findings result = InvestigationRules.evaluate(input);
        if (reporter != null) {
            reporter.step(AgentStepType.DIAGNOSIS, STEP_SYNTHESIS, StepStatus.COMPLETED,
                    InvestigationRules.describeVerdicts(result.hypotheses()));
        }
        this.findings = result;
        return Map.of();
    }

    /** 实例步骤的跳过判定：只在「路由已经查过」时成立，否则实例数据本身就是唯一事实来源。 */
    private String skipReason(AgentCapability capability) {
        if (capability != AgentCapability.INSTANCE_QUERY || !routeQueried) {
            return null;
        }
        if (route == null) {
            return routeRead ? "当前路由表没有匹配项，实例数据对本路径没有判定价值，跳过读取实例。"
                    : "路由数据不可用，跳过读取实例。";
        }
        if (text(route.serviceName()).isBlank()) {
            return "该路由配置为静态上游（无动态服务目标），跳过读取实例。";
        }
        if (discovery != DiscoveryMode.NAMESERVER) {
            return "Gateway 服务发现模式为 " + discovery + "，Nameserver 实例数据不能用于判断该路由，跳过读取实例。";
        }
        return null;
    }

    /** 把能力产出的事实并入结构化快照，供结论合成使用（与证据同源，口径必然一致）。 */
    private void applyFacts(AgentCapability capability, CapabilityResult result) {
        if (capability == AgentCapability.ROUTE_QUERY) {
            routeQueried = true;
            routeRead = result.routeRead();
            route = result.route();
            discovery = result.discoveryMode();
        } else if (capability == AgentCapability.INSTANCE_QUERY && result.executed()) {
            instances = result.instances();
        } else if (capability == AgentCapability.GATEWAY_METRICS_QUERY && result.executed()) {
            routeUpstreams = result.routeUpstreams();
        } else if (capability == AgentCapability.TRACE_QUERY && result.executed()) {
            traces = result.traces();
        }
    }

    private void report(AgentCapability capability) {
        if (!executedCapabilities.contains(capability)) {
            executedCapabilities.add(capability);
        }
        if (reporter != null) {
            reporter.reportCapabilityExecuted(capability);
        }
    }

    private PlanningRequest planningRequest() {
        return new PlanningRequest(question, target, path, List.copyOf(evidence), List.copyOf(limitations));
    }

    private static String describePlan(int round, InvestigationPlan plan) {
        StringBuilder text = new StringBuilder("第 ").append(round).append(" 轮计划：")
                .append(plan.goal().isBlank() ? "继续核实" : plan.goal());
        for (PlannedStep step : plan.steps()) {
            text.append("\n- ").append(step.capability()).append("：").append(step.reason());
        }
        return cut(text.toString());
    }

    private static String detailOf(CapabilityResult result) {
        if (result.evidence().isEmpty()) {
            return LIMIT_REACHED.equals(limitationOf(result)) ? limitationOf(result) : "本次能力未产出证据";
        }
        StringBuilder text = new StringBuilder();
        for (Evidence item : result.evidence()) {
            text.append(text.length() == 0 ? "" : "；").append(item.summary());
        }
        return cut(text.toString());
    }

    private static String limitationOf(CapabilityResult result) {
        return result.limitations().isEmpty() ? "能力执行未产出证据" : result.limitations().get(0);
    }

    /**
     * 能力对应的步骤类型。
     *
     * 刻意穷举、不写 default：步骤名与类型必须与 {@code CapabilityRegistry} 注册的一致，
     * 否则「执行中」与「已完成」两次上报会因步骤名不同而无法合并，时间线上会留下一条永远在转的步骤。
     * 将来新增能力时，这里不补分支就编译不过——把「对齐」交给编译器而不是靠人记得。
     */
    private static AgentStepType stepType(AgentCapability capability) {
        return switch (capability) {
            case ROUTE_QUERY -> AgentStepType.ROUTE_INVESTIGATION;
            case INSTANCE_QUERY -> AgentStepType.INSTANCE_INVESTIGATION;
            case GATEWAY_METRICS_QUERY -> AgentStepType.METRIC_INVESTIGATION;
            case TRACE_QUERY -> AgentStepType.TRACE_INVESTIGATION;
            case CONFIG_READ -> AgentStepType.CONFIG_INVESTIGATION;
            case EVENT_QUERY -> AgentStepType.EVENT_INVESTIGATION;
            case LOG_QUERY -> AgentStepType.LOG_INVESTIGATION;
            case KNOWLEDGE_RETRIEVAL -> AgentStepType.KNOWLEDGE_INVESTIGATION;
        };
    }

    /** 能力对应的步骤名；必须与 {@code CapabilityRegistry} 的 {@code stepName} 逐字一致，见上。 */
    private static String stepName(AgentCapability capability) {
        return switch (capability) {
            case ROUTE_QUERY -> "读取路由";
            case INSTANCE_QUERY -> "读取实例";
            case GATEWAY_METRICS_QUERY -> "读取指标";
            case TRACE_QUERY -> "读取追踪";
            case CONFIG_READ -> "读取配置";
            case EVENT_QUERY -> "读取事件";
            case LOG_QUERY -> "读取历史日志";
            case KNOWLEDGE_RETRIEVAL -> "检索知识";
        };
    }

    /**
     * 取数中的说明：等待期要让人看见「正在查什么」，而不是只有一个转圈。
     *
     * 这里是能力级别的具体动作，与步骤名（简短标签）互补——标签给进度，这句给内容。
     */
    private static String runningText(AgentCapability capability) {
        return switch (capability) {
            case ROUTE_QUERY -> "正在读取 Gateway 路由表与目标服务";
            case INSTANCE_QUERY -> "正在读取注册中心实例与健康状态";
            case GATEWAY_METRICS_QUERY -> "正在读取网关指标与按上游实例的窗口观测";
            case TRACE_QUERY -> "正在读取该路径的抽样追踪";
            case CONFIG_READ -> "正在读取 Gateway 与 NameServer 生效配置";
            case EVENT_QUERY -> "正在读取注册中心近期事件";
            case LOG_QUERY -> "正在读取落盘历史日志";
            case KNOWLEDGE_RETRIEVAL -> "正在检索运维知识库";
        };
    }

    /**
     * 规划中的说明：第 1 轮是从零规划，之后是按已采到的证据决定补查什么。
     *
     * 说明里带上已采集的证据条数，等待期看到的就是「它现在手里有什么、正在想什么」。
     */
    private String planningRunning(int round) {
        if (round <= 1) {
            return "正在根据问题与目标规划需要查询的只读能力";
        }
        return "已采集 " + evidence.size() + " 条证据，正在判断还需要补充哪些事实";
    }

    private static String cut(String text) {
        String value = text == null ? "" : text;
        return value.length() > MAX_DETAIL_LENGTH ? value.substring(0, MAX_DETAIL_LENGTH) + "…" : value;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}