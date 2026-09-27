package com.rover.agent.runtime.tool;

import com.rover.agent.core.model.Evidence;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.springframework.ai.tool.annotation.Tool;

/** 模型只能读取本次调查已采集的快照，不能访问管理接口，也不能产生任何写操作。 */
public final class SnapshotTools {

    private static final String TOOL_ROUTE = "路由快照";
    private static final String TOOL_INSTANCE = "实例快照";
    private static final String TOOL_METRICS = "指标快照";
    private static final String TOOL_TRACE = "追踪快照";
    private static final String TOOL_UPSTREAM = "上游实例窗口观测";
    private static final String TOOL_CONFIG = "配置快照";
    private static final String TOOL_EVENT = "事件快照";

    /** 运行时预读的核心快照地址：路由与实例是任何解释的最小依据，不依赖模型自觉调用。 */
    private static final String ROUTE_REFERENCE = "/api/routes";
    private static final String INSTANCE_REFERENCE = "/api/instances";

    /** 其余按需读取的证据地址：模型只在需要时才调这些工具。 */
    private static final String LIVE_REFERENCE = "/api/live?range=60";
    private static final String TRACE_REFERENCE_PREFIX = "/api/traces?";
    private static final String UPSTREAM_REFERENCE_PREFIX = "/api/metrics/routes?";
    private static final String CONFIG_REFERENCE = "/api/configs";
    private static final String EVENT_REFERENCE = "/api/events";

    private final Map<String, String> snapshots;
    /** 原始证据列表：按前缀取回时用，地址索引会合并同址证据，装不下「一跳一条」的观测行。 */
    private final List<Evidence> evidence;
    /** 模型本次实际读过的工具，按首次调用顺序记录：解释里要能说清「读了哪几份证据」。 */
    private final CopyOnWriteArrayList<String> calls = new CopyOnWriteArrayList<>();

    public SnapshotTools(List<Evidence> evidence) {
        this.evidence = List.copyOf(evidence);
        // 按取数地址建索引：工具的入参是来源地址，不是给人看的来源名。
        // 同一地址出现多条时保留第一条：索引只为取回快照文本，重复地址不该让整段解读失败。
        this.snapshots = evidence.stream().collect(Collectors.toMap(Evidence::rawReference, Evidence::summary,
                (first, second) -> first));
    }

    @Tool(description = "读取当前请求路径匹配到的 Gateway 路由配置快照：匹配前缀、目标服务、超时等转发规则。"
            + "问「这条路径打到哪个服务」「路由是怎么配的」时用它。"
            + "它只说明转发规则本身，没有流量与错误数据；要判断请求是否失败，请配合实例快照、指标或追踪快照。")
    public String routeSnapshot() {
        calls.addIfAbsent(TOOL_ROUTE);
        return routeText();
    }

    @Tool(description = "读取 Nameserver 的注册实例以及目标服务的健康实例快照：实例地址、健康状态、所属服务。"
            + "问「某个服务有几个健康实例」「实例还在不在」时用它。"
            + "它只说明注册与健康事实，不说明实例响应快慢或返回码；后者要用上游实例窗口观测或追踪快照。")
    public String instanceSnapshot() {
        calls.addIfAbsent(TOOL_INSTANCE);
        return instanceText();
    }

    @Tool(description = "读取最近一分钟 Gateway 的全局流量与拒绝计数快照（请求数、拒绝数、观测时间）。"
            + "问「网关整体 QPS / 拒绝多少」这类全局口径时用它。"
            + "它是整个网关的合计值，不是单一路由或单一实例；要看某条路由或某台实例的失败情况，"
            + "请用上游实例窗口观测，那里的样本量与统计窗口才可比。")
    public String metricsSnapshot() {
        return read(TOOL_METRICS, LIVE_REFERENCE, "实时指标不可用");
    }

    @Tool(description = "读取该路由各上游实例在统计窗口内的请求数、5xx 数、连接失败数、超时数与延迟，每台实例一行。"
            + "定位「是哪台实例异常」「是不是个别实例拖慢了整条路由」时用它。"
            + "每行都带统计窗口与样本量，样本很少时不能据此下结论；窗口内没有转发记录时应如实说无数据，"
            + "不要当成「没有问题」。")
    public String upstreamSnapshot() {
        return readJoined(TOOL_UPSTREAM, UPSTREAM_REFERENCE_PREFIX, "按上游实例的指标不可用");
    }

    @Tool(description = "读取当前请求路径的 Gateway 抽样追踪记录：一次调用经过的各跳及其阶段耗时与状态码。"
            + "需要看清「一次调用是在哪一跳慢下来或失败的」时用它。"
            + "追踪是抽样的，采样率可能很低，也可能一条都没有；没有记录不等于没有发生故障，"
            + "此时应改用实例快照或上游实例窗口观测来判断。")
    public String traceSnapshot() {
        return readJoined(TOOL_TRACE, TRACE_REFERENCE_PREFIX, "追踪数据不可用");
    }

    @Tool(description = "读取 Gateway 与 Nameserver 当前生效配置快照（限流、熔断、超时、采样率等）。"
            + "问「阈值是多少」「是不是配置把请求挡下来了」时用它。"
            + "它只说明配置的生效值，不代表运行态真的按该配置工作，也不含变更历史；"
            + "要确认实际生效效果，请配合指标或追踪快照。")
    public String configSnapshot() {
        return read(TOOL_CONFIG, CONFIG_REFERENCE, "配置快照不可用");
    }

    @Tool(description = "读取 Nameserver 最近的注册事件快照（注册、注销、剔除、标记不健康）。"
            + "问「最近有没有实例上下线」「实例是什么时候掉的」时用它。"
            + "它只记录注册中心的变更经过，不含请求失败原因，也不含 Gateway 侧的转发异常。")
    public String eventSnapshot() {
        return read(TOOL_EVENT, EVENT_REFERENCE, "注册事件不可用");
    }

    /**
     * 运行时预读的核心快照（路由 + 实例），按固定顺序返回，供解释提示词直接引用。
     *
     * 路由与实例是任何解释的最小依据，实测模型只会读它自认为需要的快照，因此这两份由运行时保证提供；
     * 预读不写入工具调用记录，步骤说明里「运行时预读」与「模型另调」各自如实记录。
     */
    public Map<String, String> coreSnapshots() {
        Map<String, String> core = new LinkedHashMap<>();
        core.put(TOOL_ROUTE, routeText());
        core.put(TOOL_INSTANCE, instanceText());
        return core;
    }

    /** 本次解读实际调用过的工具名（按首次调用顺序）；重复调用同一工具只记一次。 */
    public List<String> calledTools() {
        return List.copyOf(calls);
    }

    private String routeText() {
        return snapshots.getOrDefault(ROUTE_REFERENCE, "路由数据不可用");
    }

    private String instanceText() {
        return snapshots.getOrDefault(INSTANCE_REFERENCE, "实例数据不可用");
    }

    private String read(String tool, String reference, String fallback) {
        calls.addIfAbsent(tool);
        return snapshots.getOrDefault(reference, fallback);
    }

    /**
     * 取回同一地址前缀下的全部快照文本。
     *
     * 「路由 × 上游实例」一次会产出多条同前缀证据（每个实例一条），因此这里按前缀匹配原始证据列表并逐条拼接，
     * 而不是查地址索引或只取第一条——地址索引会把同址证据合成一条，只取第一条会让模型看到一台实例就以为那是全部。
     */
    private String readJoined(String tool, String referencePrefix, String fallback) {
        calls.addIfAbsent(tool);
        String joined = evidence.stream()
                .filter(item -> item.rawReference() != null && item.rawReference().startsWith(referencePrefix))
                .map(Evidence::summary).collect(Collectors.joining("；"));
        return joined.isBlank() ? fallback : joined;
    }
}