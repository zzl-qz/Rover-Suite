package com.rover.agent.runtime.tool;

import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.capability.CapabilityExecutor;
import com.rover.agent.core.capability.CapabilityResult;
import com.rover.agent.core.investigation.Findings;
import com.rover.agent.core.investigation.FindingsInput;
import com.rover.agent.core.investigation.InvestigationRules;
import com.rover.agent.core.model.AgentStepType;
import com.rover.agent.core.model.CheckpointStage;
import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.StepStatus;
import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import com.rover.agent.core.snapshot.RouteUpstreamSnapshot;
import com.rover.agent.core.snapshot.TraceSnapshot;
import com.rover.agent.runtime.task.InvestigationTask;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Agent 的只读查询工具集：模型自己决定查什么，每次调用都是一次真实取数。 */
public final class OpsTools {

    /** 单次任务的工具调用上限，防止模型反复查询停不下来。 */
    private static final int MAX_CALLS = 30;

    private static final String STEP_ROUTE = "读取路由";
    private static final String STEP_INSTANCE = "读取实例";
    private static final String STEP_METRIC = "读取指标";
    private static final String STEP_TRACE = "读取追踪";
    private static final String STEP_CONFIG = "读取配置";
    private static final String STEP_EVENT = "读取事件";
    private static final String STEP_LOG = "读取历史日志";
    private static final String STEP_KNOWLEDGE = "检索知识";

    private final CapabilityExecutor executor;
    private final InvestigationTask task;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<Evidence> evidence = new CopyOnWriteArrayList<>();
    private final List<String> limitations = new CopyOnWriteArrayList<>();
    /** 与证据同源的结构化快照，供调查结束后用规则判定假设。只认「按路径查过路由」的那一次。 */
    private boolean routeQueried;
    private String investigatedPath = "";
    private boolean routeRead;
    private RouteSnapshot route;
    private DiscoveryMode discovery = DiscoveryMode.UNKNOWN;
    private List<InstanceSnapshot> instances;
    private TraceSnapshot traces;
    private List<RouteUpstreamSnapshot> routeUpstreams;

    public OpsTools(CapabilityExecutor executor, InvestigationTask task) {
        this.executor = executor;
        this.task = task;
    }

    /** 列出 Gateway 上配置的全部路由。 */
    @Tool(description = "列出 Gateway 上配置的全部路由：每条的前缀、目标服务（含分组）或静态目标地址。"
            + "问「一共有几条路由」「路由都指向哪里」「有没有配错的路由」时用它。"
            + "它只说明路由配置本身，不含流量与健康数据；要判断某条路由是否通，请配合网关指标。")
    public String listRoutes() {
        return query(AgentCapability.ROUTE_QUERY, AgentStepType.ROUTE_INVESTIGATION, STEP_ROUTE,
                "正在读取 Gateway 全部路由配置", ResourceTarget.unknown(), "");
    }

    /** 查询路径匹配到的 Gateway 路由配置。 */
    @Tool(description = "查询请求路径匹配到的 Gateway 路由配置：匹配前缀、目标服务与版本（group）、静态目标地址、是否剥离前缀。"
            + "问「这条路径打到哪个服务」「路由是怎么配的」「灰度是不是配的这条」时用它。"
            + "不传路径时列出全部路由。它只说明转发规则本身，不含任何流量与错误数据；"
            + "要判断请求成功与否，请配合实例清单、网关指标或追踪。")
    public String getRoute(@ToolParam(description = "请求路径，例如 /api/demo/tt；留空表示列出全部路由") String path) {
        String target = text(path);
        if (target.isBlank()) {
            return listRoutes();
        }
        return query(AgentCapability.ROUTE_QUERY, AgentStepType.ROUTE_INVESTIGATION, STEP_ROUTE,
                "正在匹配路径 " + target + " 对应的 Gateway 路由", ResourceTarget.route(target), target);
    }

    /** 查询注册中心的实例清单；不传服务名时返回全部实例。 */
    @Tool(description = "查询注册中心的实例清单：地址、端口、健康状态、所属服务。"
            + "不传服务名时返回全部注册实例，适合「现在都有哪些实例」「一共几个实例」这类问题；"
            + "传服务名时只列该服务的实例，适合「order-service 有几个健康实例」。"
            + "它只说明注册与健康事实，不说明实例响应快慢或返回码；后者要用网关指标或追踪。")
    public String listInstances(@ToolParam(description = "服务名，例如 order-service；留空表示查询全部注册实例")
                                String serviceName) {
        String service = text(serviceName);
        String running = service.isBlank()
                ? "正在读取注册中心全部实例与健康状态"
                : "正在读取服务 " + service + " 的实例与健康状态";
        ResourceTarget target = service.isBlank() ? ResourceTarget.unknown() : ResourceTarget.service(service);
        return query(AgentCapability.INSTANCE_QUERY, AgentStepType.INSTANCE_INVESTIGATION, STEP_INSTANCE,
                running, target, "");
    }

    /** 查询 Gateway 最近一分钟的流量与拒绝计数；传路径时附带该路由各上游实例的窗口观测。 */
    @Tool(description = "查询 Gateway 最近一分钟的流量与拒绝计数：请求数、5xx 数、无上游拒绝累计。"
            + "传入请求路径时，还会附带该路径所属路由「按上游实例」的窗口观测（每台实例的请求数、5xx、"
            + "连接失败、超时与延迟），这是定位「是哪台实例出问题」的依据。"
            + "全局计数只能说明「有没有异常」，实例维度才说明「是哪台」；样本量不足时会明确说明，"
            + "不要据此判定某一台实例异常。")
    public String getGatewayMetrics(@ToolParam(description = "请求路径，例如 /api/demo/tt；留空则只查全局计数")
                                    String path) {
        String target = text(path);
        String running = target.isBlank()
                ? "正在读取 Gateway 全局流量与拒绝计数"
                : "正在读取网关指标与 " + target + " 各上游实例的窗口观测";
        ResourceTarget resource = target.isBlank() ? ResourceTarget.unknown() : ResourceTarget.route(target);
        return query(AgentCapability.GATEWAY_METRICS_QUERY, AgentStepType.METRIC_INVESTIGATION, STEP_METRIC,
                running, resource, target);
    }

    /** 查询某个路径的抽样追踪。 */
    @Tool(description = "查询某个请求路径的 Gateway 抽样追踪：一次调用经过的各跳及其阶段耗时与状态码。"
            + "需要看清「一次调用是在哪一跳慢下来或失败的」时用它。"
            + "追踪是抽样的，采样率可能很低、也可能一条都没有；没有记录不等于没有发生故障，"
            + "此时应改用实例清单或网关指标判断。")
    public String getTraces(@ToolParam(description = "请求路径，例如 /api/demo/tt") String path) {
        String target = text(path);
        if (target.isBlank()) {
            return "需要给出请求路径才能查询追踪，例如 /api/demo/tt。";
        }
        return query(AgentCapability.TRACE_QUERY, AgentStepType.TRACE_INVESTIGATION, STEP_TRACE,
                "正在读取路径 " + target + " 的抽样追踪", ResourceTarget.route(target), target);
    }

    /** 查询 Gateway 与 Nameserver 当前生效的配置项。 */
    @Tool(description = "查询 Gateway 与 Nameserver 当前生效的配置项（限流、熔断、超时、采样率等）。"
            + "问「阈值配的多少」「是不是配置把请求挡下来了」时用它。"
            + "它只说明配置的生效值，不代表运行态真的按该配置工作，也不含变更历史。")
    public String getConfigs() {
        return query(AgentCapability.CONFIG_READ, AgentStepType.CONFIG_INVESTIGATION, STEP_CONFIG,
                "正在读取 Gateway 与 Nameserver 生效配置", ResourceTarget.unknown(), "");
    }

    /** 查询注册中心最近的注册、注销、剔除与推送事件。 */
    @Tool(description = "查询注册中心最近的事件：实例注册、注销、剔除、标记不健康、推送。"
            + "问「最近有没有实例上下线」「这个实例是什么时候掉的」时用它。"
            + "它只记录注册中心的变更经过，不含请求失败原因，也不含 Gateway 侧的转发异常。")
    public String listRegistryEvents() {
        return query(AgentCapability.EVENT_QUERY, AgentStepType.EVENT_INVESTIGATION, STEP_EVENT,
                "正在读取注册中心近期事件", ResourceTarget.unknown(), "");
    }

    /** 查询落盘历史日志：配置变更、回滚、错误、实例上下线、指标采样、慢/错误链路。 */
    @Tool(description = "查询落盘历史日志：配置变更、回滚、运维错误、实例上下线事件、指标采样、慢请求与错误链路。"
            + "问「最近改过什么配置」「有没有回滚过」「这个实例是什么时候掉线的」「最近出过什么错」「哪条链路慢」时用它。"
            + "type 精确过滤：config_change（配置变更）、rollback（回滚）、error（运维错误）、"
            + "instance_event（实例上下线）、metrics_sample（指标采样）、request_trace（慢/错误链路）；不传表示全部类型。"
            + "hours 表示查最近几小时，默认 24；不传 target 表示查全部实体。"
            + "它反映历史经过，不是当前实时快照；要查当前状态请用路由、实例、指标或追踪工具。")
    public String queryLogs(
            @ToolParam(description = "日志类型，可选：config_change / rollback / error / instance_event / "
                    + "metrics_sample / request_trace；留空表示全部类型") String type,
            @ToolParam(description = "目标实体：服务名、路由路径或 ip:port；留空表示查全部实体") String target,
            @ToolParam(description = "查最近几小时，默认 24") Integer hours) {
        if (!withinBudget()) {
            return "本次对话的查询次数已达上限（" + MAX_CALLS + " 次）。请立刻基于已经取回的事实作答，"
                    + "仍不确定的部分如实说明无法确认，不要再请求新的查询。";
        }
        List<String> types = text(type).isBlank() ? null : List.of(text(type));
        Long from = hours == null || hours <= 0 ? null : System.currentTimeMillis() - hours * 3_600_000L;
        String entity = text(target);
        task.step(AgentStepType.LOG_INVESTIGATION, STEP_LOG, StepStatus.RUNNING,
                entity.isBlank() ? "正在查询历史日志" : "正在查询 " + entity + " 的历史日志");
        CapabilityResult result = executor.queryLogs(entity, types, from, null, task.taskId());
        task.reportCapabilityExecuted(AgentCapability.LOG_QUERY);
        evidence.addAll(result.evidence());
        limitations.addAll(result.limitations());
        String rendered = render(result);
        task.step(AgentStepType.LOG_INVESTIGATION, STEP_LOG, StepStatus.COMPLETED,
                result.evidence().isEmpty() ? rendered : brief(result));
        markSafePoint(result);
        return rendered;
    }

    /** 检索运维知识库：怎么配置限流/熔断/超时/采样率、怎么接入服务、怎么做灰度、怎么排查。 */
    @Tool(description = "检索运维知识库，回答「怎么配置 / 怎么接入 / 怎么排查」这类使用方式问题。"
            + "问「限流怎么配置」「怎么接入一个服务」「怎么做灰度发布」「怎么摘除异常实例」时用它。"
            + "它返回文档/经验，不是当前实时状态；要查当前生效值请用配置或指标工具。")
    public String searchKnowledge(@ToolParam(description = "要检索的问题，例如「怎么配置限流阈值」") String query) {
        if (text(query).isBlank()) {
            return "需要给出要检索的问题，例如「怎么配置限流阈值」。";
        }
        return query(AgentCapability.KNOWLEDGE_RETRIEVAL, AgentStepType.KNOWLEDGE_INVESTIGATION, STEP_KNOWLEDGE,
                "正在检索运维知识库", ResourceTarget.unknown(), text(query));
    }

    /** 本次对话取回的全部证据。 */
    public List<Evidence> evidence() {
        return List.copyOf(evidence);
    }

    /** 取数过程中的判断边界，如数据缺失、样本不足。 */
    public List<String> limitations() {
        return List.copyOf(limitations);
    }

    /** 本次实际调用工具的次数。 */
    public int callCount() {
        return calls.get();
    }

    /**
     * 用本次已经取回的快照做假设判定。
     *
     * 没按路径查过路由时返回空，避免把「只列了路由清单」误判成路径未命中。
     */
    public Findings judge() {
        if (!routeQueried) {
            return null;
        }
        String path = task.path() == null || task.path().isBlank() ? investigatedPath : task.path();
        return InvestigationRules.evaluate(new FindingsInput(path, route, routeRead, discovery,
                instances, traces, routeUpstreams));
    }

    /** 模型按路径查过的那条路径。目标解析没认出来时，用它作为资源笔记的键。 */
    public String investigatedPath() {
        return investigatedPath == null ? "" : investigatedPath;
    }

    /**
     * 所有工具共用的执行路径：计数、上报步骤、真实取数、累积证据。
     *
     * 额度用尽后不再取数，返回一段说明让模型收尾——静默丢弃会让模型以为查过了。
     */
    private String query(AgentCapability capability, AgentStepType stepType, String stepName, String runningText,
                         ResourceTarget target, String path) {
        if (!withinBudget()) {
            return "本次对话的查询次数已达上限（" + MAX_CALLS + " 次）。请立刻基于已经取回的事实作答，"
                    + "仍不确定的部分如实说明无法确认，不要再请求新的查询。";
        }
        task.step(stepType, stepName, StepStatus.RUNNING, runningText);
        CapabilityResult result = executor.execute(capability, target, path == null ? "" : path, task.taskId());
        task.reportCapabilityExecuted(capability);
        evidence.addAll(result.evidence());
        limitations.addAll(result.limitations());
        remember(result, path);
        String text = render(result);
        task.step(stepType, stepName, StepStatus.COMPLETED, result.evidence().isEmpty() ? text : brief(result));
        markSafePoint(result);
        return text;
    }

    /**
     * 工具已返回、步骤已终态：把证据交给任务，并推进一个安全恢复点。
     *
     * 这两步连在一起，含义是「这一次取到的事实已经确定」。崩在调用途中的话这里不会执行，
     * 恢复时重做这一次只读调用即可——只读工具重做不产生副作用，也没有需要补偿的外部状态。
     */
    private void markSafePoint(CapabilityResult result) {
        task.recordEvidence(result.evidence());
        task.checkpoint(CheckpointStage.TOOL_COMPLETED, 0, calls.get(), null);
    }

    /** 只保留规则判定要用的快照，和调查图里的取舍一致。 */
    private void remember(CapabilityResult result, String path) {
        if (result.capability() == AgentCapability.ROUTE_QUERY) {
            boolean lookedUp = result.evidence().stream().anyMatch(item -> "路由匹配".equals(item.title()));
            if (!lookedUp) {
                return;
            }
            routeQueried = true;
            if (path != null && !path.isBlank()) {
                investigatedPath = path;
            }
            routeRead = result.routeRead();
            route = result.route();
            discovery = result.discoveryMode();
        } else if (result.capability() == AgentCapability.INSTANCE_QUERY && result.executed()) {
            instances = result.instances();
        } else if (result.capability() == AgentCapability.GATEWAY_METRICS_QUERY && result.executed()) {
            routeUpstreams = result.routeUpstreams();
        } else if (result.capability() == AgentCapability.TRACE_QUERY && result.executed()) {
            traces = result.traces();
        }
    }

    /** 预留一次调用额度。 */
    private boolean withinBudget() {
        return calls.incrementAndGet() <= MAX_CALLS;
    }

    /** 交给模型的事实文本：逐条证据 + 取数边界（边界必须一起给，否则模型会把「没采到」读成「没问题」）。 */
    private static String render(CapabilityResult result) {
        StringBuilder text = new StringBuilder();
        for (Evidence item : result.evidence()) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append("- ").append(item.summary());
        }
        if (text.isEmpty()) {
            text.append(result.limitations().isEmpty()
                    ? "本次没有取到任何事实。" : String.join("；", result.limitations()));
        }
        if (!result.limitations().isEmpty() && !result.evidence().isEmpty()) {
            text.append("\n（取数边界：").append(String.join("；", result.limitations())).append("）");
        }
        return text.toString();
    }

    /** 步骤上的结果摘要：只取第一条证据与条数，不把整份快照塞进时间线。 */
    private static String brief(CapabilityResult result) {
        String first = result.evidence().get(0).summary();
        int extra = result.evidence().size() - 1;
        return extra <= 0 ? first : first + "（另有 " + extra + " 条证据）";
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
