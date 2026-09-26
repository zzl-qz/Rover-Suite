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

    /** 运行时预读的核心快照地址：路由与实例是任何解释的最小依据，不依赖模型自觉调用。 */
    private static final String ROUTE_REFERENCE = "/api/routes";
    private static final String INSTANCE_REFERENCE = "/api/instances";

    private final Map<String, String> snapshots;
    /** 模型本次实际读过的工具，按首次调用顺序记录：解释里要能说清「读了哪几份证据」。 */
    private final CopyOnWriteArrayList<String> calls = new CopyOnWriteArrayList<>();

    public SnapshotTools(List<Evidence> evidence) {
        // 按取数地址建索引：工具的入参是来源地址，不是给人看的来源名。
        // 同一地址出现多条时保留第一条：索引只为取回快照文本，重复地址不该让整段解读失败。
        this.snapshots = evidence.stream().collect(Collectors.toMap(Evidence::rawReference, Evidence::summary,
                (first, second) -> first));
    }

    @Tool(description = "读取当前请求路径匹配到的 Gateway 路由配置快照")
    public String routeSnapshot() {
        calls.addIfAbsent(TOOL_ROUTE);
        return routeText();
    }

    @Tool(description = "读取 Nameserver 注册实例及目标服务健康实例快照")
    public String instanceSnapshot() {
        calls.addIfAbsent(TOOL_INSTANCE);
        return instanceText();
    }

    @Tool(description = "读取最近一分钟 Gateway 全局流量和拒绝计数快照；不是单一路由指标")
    public String metricsSnapshot() {
        return read(TOOL_METRICS, "/api/live?range=60", "实时指标不可用");
    }

    @Tool(description = "读取当前请求路径的 Gateway 抽样追踪快照")
    public String traceSnapshot() {
        calls.addIfAbsent(TOOL_TRACE);
        return snapshots.entrySet().stream().filter(item -> item.getKey().startsWith("/api/traces?"))
                .map(Map.Entry::getValue).findFirst().orElse("追踪数据不可用");
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
}