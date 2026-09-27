package com.rover.agent.runtime.tool;

import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.capability.CapabilityExecutor;
import com.rover.agent.core.capability.CapabilityResult;
import com.rover.agent.core.model.AgentStepType;
import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.StepStatus;
import com.rover.agent.runtime.task.InvestigationTask;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Agent 的只读查询工具集：模型自己决定查什么，每次调用都是一次真实取数。
 *
 * <p>与「把预采集好的快照喂给模型」有本质区别：这里是<b>可执行</b>的。模型可以针对每个子问题
 * 分别取数——问实例就查实例、问配置就查配置，不需要任何人预先决定采哪些证据；某一步取回的数据
 * 还能决定下一步查什么。「观察 → 再决策」的循环因此第一次真正成立。
 *
 * <p>只读边界没有放宽：所有工具都经由 {@link CapabilityExecutor} 这唯一通道，它只认能力枚举，
 * 而六个能力在注册表里全部声明为只读。模型再自由也写不出任何东西——边界由结构保证，
 * 不依赖提示词的自觉。
 *
 * <p>每次调用都在任务时间线上留一条步骤，因此「模型查了什么」对用户是可见的，
 * 而不是黑箱里发生的若干次 HTTP 调用。
 */
public final class OpsTools {

    /**
     * 单次任务的工具调用上限。
     *
     * <p>这不是为了省钱，是防止模型陷入「查了再查、总觉得还不够」的空转：一个诊断问题通常在
     * 五次以内就能凑齐事实，给到 {@code 30} 次是留足余量的安全阀。触顶后不再取数，改为明确
     * 告诉模型「预算用尽、请基于已有事实作答」——让它收尾，而不是让整次对话挂死。
     */
    private static final int MAX_CALLS = 30;

    private static final String STEP_ROUTE = "读取路由";
    private static final String STEP_INSTANCE = "读取实例";
    private static final String STEP_METRIC = "读取指标";
    private static final String STEP_TRACE = "读取追踪";
    private static final String STEP_CONFIG = "读取配置";
    private static final String STEP_EVENT = "读取事件";

    private final CapabilityExecutor executor;
    private final InvestigationTask task;
    private final AtomicInteger calls = new AtomicInteger();
    /** 本次对话取回的全部证据，按调用顺序累积：结论要能锚在这些事实上，而不是模型自己说的话。 */
    private final List<Evidence> evidence = new CopyOnWriteArrayList<>();
    /** 取数过程中遇到的判断边界（数据缺失、样本不足、口径限制），随证据一起交给结论。 */
    private final List<String> limitations = new CopyOnWriteArrayList<>();

    public OpsTools(CapabilityExecutor executor, InvestigationTask task) {
        this.executor = executor;
        this.task = task;
    }

    @Tool(description = "查询请求路径匹配到的 Gateway 路由配置：匹配前缀、目标服务与版本（group）、粘性头、是否剥离前缀。"
            + "问「这条路径打到哪个服务」「路由是怎么配的」「灰度是不是配的这条」时用它。"
            + "它只说明转发规则本身，不含任何流量与错误数据；要判断请求成功与否，请配合实例清单、网关指标或追踪。")
    public String getRoute(@ToolParam(description = "请求路径，例如 /api/demo/tt") String path) {
        String target = text(path);
        if (target.isBlank()) {
            return "需要给出请求路径才能匹配路由，例如 /api/demo/tt。";
        }
        return query(AgentCapability.ROUTE_QUERY, AgentStepType.ROUTE_INVESTIGATION, STEP_ROUTE,
                "正在匹配路径 " + target + " 对应的 Gateway 路由", ResourceTarget.route(target), target);
    }

    @Tool(description = "查询注册中心的实例清单：地址、端口、健康状态、所属服务。"
            + "不传服务名时返回全部注册实例，适合「现在都有哪些实例」「一共几个实例」这类问题；"
            + "传服务名时只列该服务的实例，适合「order-service 有几个健康实例」。"
            + "它只说明注册与健康事实，不说明实例响应快慢或返回码；后者要用网关指标或追踪。")
    public String listInstances(@ToolParam(description = "服务名，例如 order-service；留空表示查询全部注册实例") String serviceName) {
        String service = text(serviceName);
        String running = service.isBlank()
                ? "正在读取注册中心全部实例与健康状态"
                : "正在读取服务 " + service + " 的实例与健康状态";
        ResourceTarget target = service.isBlank() ? ResourceTarget.unknown() : ResourceTarget.service(service);
        return query(AgentCapability.INSTANCE_QUERY, AgentStepType.INSTANCE_INVESTIGATION, STEP_INSTANCE,
                running, target, "");
    }

    @Tool(description = "查询 Gateway 最近一分钟的流量与拒绝计数：请求数、5xx 数、无上游拒绝累计。"
            + "传入请求路径时，还会附带该路径所属路由「按上游实例」的窗口观测（每台实例的请求数、5xx、"
            + "连接失败、超时与延迟），这是定位「是哪台实例出问题」的依据。"
            + "全局计数只能说明「有没有异常」，实例维度才说明「是哪台」；样本量不足时会明确说明，"
            + "不要据此判定某一台实例异常。")
    public String getGatewayMetrics(@ToolParam(description = "请求路径，例如 /api/demo/tt；留空则只查全局计数") String path) {
        String target = text(path);
        String running = target.isBlank()
                ? "正在读取 Gateway 全局流量与拒绝计数"
                : "正在读取网关指标与 " + target + " 各上游实例的窗口观测";
        ResourceTarget resource = target.isBlank() ? ResourceTarget.unknown() : ResourceTarget.route(target);
        return query(AgentCapability.GATEWAY_METRICS_QUERY, AgentStepType.METRIC_INVESTIGATION, STEP_METRIC,
                running, resource, target);
    }

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

    @Tool(description = "查询 Gateway 与 Nameserver 当前生效的配置项（限流、熔断、超时、采样率等）。"
            + "问「阈值配的多少」「是不是配置把请求挡下来了」时用它。"
            + "它只说明配置的生效值，不代表运行态真的按该配置工作，也不含变更历史。")
    public String getConfigs() {
        return query(AgentCapability.CONFIG_READ, AgentStepType.CONFIG_INVESTIGATION, STEP_CONFIG,
                "正在读取 Gateway 与 Nameserver 生效配置", ResourceTarget.unknown(), "");
    }

    @Tool(description = "查询注册中心最近的事件：实例注册、注销、剔除、标记不健康、推送。"
            + "问「最近有没有实例上下线」「这个实例是什么时候掉的」时用它。"
            + "它只记录注册中心的变更经过，不含请求失败原因，也不含 Gateway 侧的转发异常。")
    public String listRegistryEvents() {
        return query(AgentCapability.EVENT_QUERY, AgentStepType.EVENT_INVESTIGATION, STEP_EVENT,
                "正在读取注册中心近期事件", ResourceTarget.unknown(), "");
    }

    /** 本次对话取回的全部证据（按调用顺序）；结论要能锚在这些事实上。 */
    public List<Evidence> evidence() {
        return List.copyOf(evidence);
    }

    /** 取数过程中的判断边界；与证据一起交给结论，让「哪些说不准」也如实呈现。 */
    public List<String> limitations() {
        return List.copyOf(limitations);
    }

    /** 本次实际调用过的工具次数；用于在步骤与指标里说明这次对话取了多少次数。 */
    public int callCount() {
        return calls.get();
    }

    /**
     * 所有工具共用的执行路径：计数、上报步骤、真实取数、累积证据。
     *
     * <p>步骤上报沿用能力注册表里的步骤名（「读取路由」等），因此在时间线上与既有展示完全一致，
     * 用户看到的仍是「它在读什么」，而不是「模型调用了一次工具」这种内部术语。
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
        String text = render(result);
        task.step(stepType, stepName, StepStatus.COMPLETED, result.evidence().isEmpty() ? text : brief(result));
        return text;
    }

    /** 预留一次调用额度；额度用尽后不再取数，也不上报步骤。 */
    private boolean withinBudget() {
        return calls.incrementAndGet() <= MAX_CALLS;
    }

    /**
     * 交给模型的事实文本：逐条证据 + 取数边界。
     *
     * <p>边界必须一起给：只给事实不给口径，模型会把「窗口内没有记录」读成「没有问题」，
     * 而这两件事在运维上是相反的结论。
     */
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

    /** 步骤上的结果摘要：只取第一条证据 + 条数，避免把整份快照塞进时间线。 */
    private static String brief(CapabilityResult result) {
        String first = result.evidence().get(0).summary();
        int extra = result.evidence().size() - 1;
        return extra <= 0 ? first : first + "（另有 " + extra + " 条证据）";
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
