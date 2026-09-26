package com.rover.agent.runtime.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.rover.agent.core.investigation.EvidenceNarration;
import com.rover.agent.core.investigation.EvidenceNarrator;
import com.rover.agent.core.investigation.Findings;
import com.rover.agent.core.investigation.FindingsInput;
import com.rover.agent.core.investigation.InvestigationRules;
import com.rover.agent.core.investigation.RouteMatcher;
import com.rover.agent.core.model.AgentStepType;
import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.EvidenceType;
import com.rover.agent.core.model.StepStatus;
import com.rover.agent.core.port.InstanceReadPort;
import com.rover.agent.core.port.MetricReadPort;
import com.rover.agent.core.port.RouteReadPort;
import com.rover.agent.core.port.TraceReadPort;
import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.core.snapshot.GatewayMetricSnapshot;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import com.rover.agent.core.snapshot.TraceSnapshot;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 只读故障调查链：用 Spring AI Alibaba StateGraph 编排
 * 「路由采集 →（条件分支）实例采集 → 指标采集 → 追踪采集 → 结论合成」。
 *
 * 调查事实写入图状态并在节点间共享，后续走向由条件边决定：只有命中动态服务且服务发现模式为
 * Nameserver 时，实例数据才对本次路由有判定价值，否则跳过实例采集直接进入指标与追踪。
 * 具体数据由 {@code com.rover.agent.core.port} 的只读端口提供，本类不做任何数据解析，也不产生写操作。
 *
 * 每次调查构建一个图实例：运行状态会被框架序列化，因此步骤上报口与证据列表都不能放进图状态，
 * 只能由本实例持有（{@code invoke} 回读的状态里，记录内部的嵌套集合与枚举会退化成 Map / List）。
 */
public final class InvestigationGraph {

    private static final Logger log = LoggerFactory.getLogger(InvestigationGraph.class);

    /** Gateway 指标窗口：与证据来源 {@code /api/live?range=60} 保持一致。 */
    private static final int METRIC_WINDOW_SECONDS = 60;

    private static final String NODE_ROUTE = "collectRoute";
    private static final String NODE_INSTANCE = "collectInstances";
    private static final String NODE_METRICS = "collectMetrics";
    private static final String NODE_TRACE = "collectTraces";
    private static final String NODE_SYNTHESIS = "synthesise";
    private static final String BRANCH_INSTANCE = "investigateInstance";
    private static final String BRANCH_METRICS = "skipToMetrics";

    private static final String STEP_ROUTE = "读取路由";
    private static final String STEP_INSTANCE = "读取实例";
    private static final String STEP_METRICS = "读取指标";
    private static final String STEP_TRACE = "读取追踪";
    private static final String STEP_SYNTHESIS = "调查推理";

    private static final String SOURCE_ROUTES = "Gateway 路由表";
    private static final String SOURCE_OVERVIEW = "Gateway 概览";
    private static final String SOURCE_INSTANCES = "Nameserver 实例注册表";
    private static final String SOURCE_METRICS = "Gateway 实时指标";
    private static final String SOURCE_TRACES = "Gateway 抽样追踪";

    private static final String TASK_ID = "taskId";
    private static final String PATH = "path";
    private static final String ROUTE = "route";
    private static final String ROUTE_READ = "routeRead";
    private static final String DISCOVERY = "discovery";
    private static final String INSTANCES = "instances";
    private static final String TRACES = "traces";
    private static final String LIMITATIONS = "limitations";

    private final RouteReadPort routes;
    private final InstanceReadPort instances;
    private final MetricReadPort metrics;
    private final TraceReadPort traces;
    private final StepSink sink;
    private final CompiledGraph graph;
    private final List<Evidence> collectedEvidence = Collections.synchronizedList(new ArrayList<>());

    /**
     * 结论由 synthesise 节点直接产出，不放进图状态再回读：{@code invoke} 返回的状态会被框架
     * 序列化成快照，记录内部的嵌套集合与枚举在回读时会退化为 Map / List。每次调查新建一个图实例，
     * 该字段只被单次调查使用。
     */
    private Findings producedFindings;

    public InvestigationGraph(RouteReadPort routes, InstanceReadPort instances, MetricReadPort metrics,
                              TraceReadPort traces, StepSink sink) {
        this.routes = routes;
        this.instances = instances;
        this.metrics = metrics;
        this.traces = traces;
        this.sink = sink;
        this.graph = compile();
    }

    /** 执行一次只读调查，返回结论、证据与判断边界；证据与步骤都归属 {@code taskId}。 */
    public InvestigationOutcome investigate(String taskId, String path) {
        OverAllState state;
        try {
            state = graph.invoke(Map.of(TASK_ID, taskId, PATH, path)).orElseThrow(
                    () -> new IllegalStateException("调查图未返回状态"));
        } catch (Exception ex) {
            throw new IllegalStateException("调查图执行失败", ex);
        }
        Findings findings = this.producedFindings;
        if (findings == null) {
            throw new IllegalStateException("调查图未产出结论");
        }
        return new InvestigationOutcome(findings, List.copyOf(collectedEvidence), readList(state, LIMITATIONS));
    }

    private CompiledGraph compile() {
        try {
            return new StateGraph(keyStrategies())
                    .addNode(NODE_ROUTE, AsyncNodeAction.node_async(this::collectRoute))
                    .addNode(NODE_INSTANCE, AsyncNodeAction.node_async(this::collectInstances))
                    .addNode(NODE_METRICS, AsyncNodeAction.node_async(this::collectMetrics))
                    .addNode(NODE_TRACE, AsyncNodeAction.node_async(this::collectTraces))
                    .addNode(NODE_SYNTHESIS, AsyncNodeAction.node_async(this::synthesise))
                    .addEdge(StateGraph.START, NODE_ROUTE)
                    .addConditionalEdges(NODE_ROUTE, AsyncEdgeAction.edge_async(this::routeBranch),
                            Map.of(BRANCH_INSTANCE, NODE_INSTANCE, BRANCH_METRICS, NODE_METRICS))
                    .addEdge(NODE_INSTANCE, NODE_METRICS)
                    .addEdge(NODE_METRICS, NODE_TRACE)
                    .addEdge(NODE_TRACE, NODE_SYNTHESIS)
                    .addEdge(NODE_SYNTHESIS, StateGraph.END)
                    .compile();
        } catch (GraphStateException ex) {
            throw new IllegalStateException("调查图构建失败", ex);
        }
    }

    private static KeyStrategyFactory keyStrategies() {
        return () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put(TASK_ID, new ReplaceStrategy());
            strategies.put(PATH, new ReplaceStrategy());
            strategies.put(ROUTE, new ReplaceStrategy());
            strategies.put(ROUTE_READ, new ReplaceStrategy());
            strategies.put(DISCOVERY, new ReplaceStrategy());
            strategies.put(INSTANCES, new ReplaceStrategy());
            strategies.put(TRACES, new ReplaceStrategy());
            strategies.put(LIMITATIONS, new AppendStrategy());
            return strategies;
        };
    }

    /** 条件边：只有命中动态服务且服务发现模式为 Nameserver 时，实例数据才对本次路由有判定价值。 */
    private String routeBranch(OverAllState state) {
        RouteSnapshot route = state.<RouteSnapshot>value(ROUTE).orElse(null);
        if (route == null || text(route.serviceName()).isBlank()) {
            return BRANCH_METRICS;
        }
        return state.<DiscoveryMode>value(DISCOVERY).orElse(DiscoveryMode.UNKNOWN) == DiscoveryMode.NAMESERVER
                ? BRANCH_INSTANCE : BRANCH_METRICS;
    }

    private Map<String, Object> collectRoute(OverAllState state) {
        String path = state.value(PATH, "");
        String taskId = state.value(TASK_ID, "");
        Map<String, Object> updates = new HashMap<>();
        List<String> limitations = new ArrayList<>();
        RouteSnapshot route = null;
        boolean routeRead = false;
        try {
            route = RouteMatcher.match(routes.routes(), path);
            EvidenceNarration narration = EvidenceNarrator.route(route);
            sink.step(AgentStepType.ROUTE_INVESTIGATION, STEP_ROUTE, StepStatus.COMPLETED, narration.detail());
            collectedEvidence.add(Evidence.of(taskId, EvidenceType.ROUTE, SOURCE_ROUTES, "路由匹配",
                    narration.detail(), "/api/routes", System.currentTimeMillis()));
            limitations.addAll(narration.limitations());
            routeRead = true;
        } catch (Exception ex) {
            log.warn("Agent 读取路由失败", ex);
            sink.step(AgentStepType.ROUTE_INVESTIGATION, STEP_ROUTE, StepStatus.FAILED, "Gateway 路由数据不可用");
            limitations.add(EvidenceNarrator.routeUnavailable());
        }
        DiscoveryMode discovery = DiscoveryMode.UNKNOWN;
        try {
            discovery = routes.discoveryMode();
        } catch (Exception ex) {
            log.warn("Agent 读取服务发现模式失败", ex);
        }
        EvidenceNarration discoveryNarration = EvidenceNarrator.discoveryMode(discovery);
        collectedEvidence.add(Evidence.of(taskId, EvidenceType.ROUTE, SOURCE_OVERVIEW, "服务发现模式",
                discoveryNarration.detail(), "/api/overview", System.currentTimeMillis()));
        limitations.addAll(discoveryNarration.limitations());
        updates.put(ROUTE, route);
        updates.put(ROUTE_READ, routeRead);
        updates.put(DISCOVERY, discovery);
        updates.put(LIMITATIONS, limitations);
        return updates;
    }

    private Map<String, Object> collectInstances(OverAllState state) {
        String taskId = state.value(TASK_ID, "");
        Map<String, Object> updates = new HashMap<>();
        List<String> limitations = new ArrayList<>();
        try {
            List<InstanceSnapshot> snapshot = instances.instances();
            EvidenceNarration narration = EvidenceNarrator.instances(
                    snapshot, state.<RouteSnapshot>value(ROUTE).orElse(null));
            sink.step(AgentStepType.INSTANCE_INVESTIGATION, STEP_INSTANCE, StepStatus.COMPLETED, narration.detail());
            collectedEvidence.add(Evidence.of(taskId, EvidenceType.INSTANCE, SOURCE_INSTANCES, "实例健康",
                    narration.detail(), "/api/instances", System.currentTimeMillis()));
            limitations.addAll(narration.limitations());
            updates.put(INSTANCES, snapshot);
        } catch (Exception ex) {
            log.warn("Agent 读取实例失败", ex);
            sink.step(AgentStepType.INSTANCE_INVESTIGATION, STEP_INSTANCE, StepStatus.FAILED,
                    "Nameserver 实例数据不可用");
            limitations.add(EvidenceNarrator.instancesUnavailable());
        }
        updates.put(LIMITATIONS, limitations);
        return updates;
    }

    private Map<String, Object> collectMetrics(OverAllState state) {
        String taskId = state.value(TASK_ID, "");
        Map<String, Object> updates = new HashMap<>();
        List<String> limitations = new ArrayList<>();
        try {
            GatewayMetricSnapshot metric = metrics.gatewayWindow(METRIC_WINDOW_SECONDS);
            EvidenceNarration narration = EvidenceNarrator.metrics(metric);
            sink.step(AgentStepType.METRIC_INVESTIGATION, STEP_METRICS, StepStatus.COMPLETED, narration.detail());
            collectedEvidence.add(Evidence.of(taskId, EvidenceType.METRIC, SOURCE_METRICS, "最近一分钟流量",
                    narration.detail(), "/api/live?range=" + METRIC_WINDOW_SECONDS, System.currentTimeMillis()));
            limitations.addAll(narration.limitations());
        } catch (Exception ex) {
            log.warn("Agent 读取指标失败", ex);
            sink.step(AgentStepType.METRIC_INVESTIGATION, STEP_METRICS, StepStatus.FAILED,
                    "Gateway 实时指标不可用");
            limitations.add(EvidenceNarrator.metricsUnavailable());
        }
        updates.put(LIMITATIONS, limitations);
        return updates;
    }

    private Map<String, Object> collectTraces(OverAllState state) {
        String path = state.value(PATH, "");
        String taskId = state.value(TASK_ID, "");
        Map<String, Object> updates = new HashMap<>();
        List<String> limitations = new ArrayList<>();
        try {
            TraceSnapshot snapshot = traces.byPath(path);
            EvidenceNarration narration = EvidenceNarrator.traces(snapshot, path);
            sink.step(AgentStepType.TRACE_INVESTIGATION, STEP_TRACE, StepStatus.COMPLETED, narration.detail());
            collectedEvidence.add(Evidence.of(taskId, EvidenceType.TRACE, SOURCE_TRACES, "路径追踪记录",
                    narration.detail(), "/api/traces?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8),
                    System.currentTimeMillis()));
            limitations.addAll(narration.limitations());
            updates.put(TRACES, snapshot);
        } catch (Exception ex) {
            log.warn("Agent 读取追踪失败", ex);
            sink.step(AgentStepType.TRACE_INVESTIGATION, STEP_TRACE, StepStatus.FAILED,
                    "Gateway 追踪数据不可用");
            limitations.add(EvidenceNarrator.tracesUnavailable());
        }
        updates.put(LIMITATIONS, limitations);
        return updates;
    }

    /** 结论合成：只依据已采集的只读事实逐条确认或排除候选故障原因。 */
    private Map<String, Object> synthesise(OverAllState state) {
        FindingsInput input = new FindingsInput(
                state.value(PATH, ""),
                state.<RouteSnapshot>value(ROUTE).orElse(null),
                state.value(ROUTE_READ, false),
                state.<DiscoveryMode>value(DISCOVERY).orElse(DiscoveryMode.UNKNOWN),
                state.<List<InstanceSnapshot>>value(INSTANCES).orElse(null),
                state.<TraceSnapshot>value(TRACES).orElse(null));
        Findings findings = InvestigationRules.evaluate(input);
        sink.step(AgentStepType.DIAGNOSIS, STEP_SYNTHESIS, StepStatus.COMPLETED,
                InvestigationRules.describeVerdicts(findings.hypotheses()));
        this.producedFindings = findings;
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> readList(OverAllState state, String key) {
        Object value = state.value(key).orElse(null);
        return value instanceof List<?> items ? (List<T>) List.copyOf(items) : List.of();
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}