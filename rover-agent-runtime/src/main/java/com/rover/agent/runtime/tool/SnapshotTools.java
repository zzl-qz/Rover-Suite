package com.rover.agent.runtime.tool;

import com.rover.agent.core.model.Evidence;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.springframework.ai.tool.annotation.Tool;

/** 模型只能读取本次调查已采集的快照，不能访问管理接口，也不能产生任何写操作。 */
public final class SnapshotTools {

    private static final String TOOL_ROUTE = "路由快照";
    private static final String TOOL_INSTANCE = "实例快照";
    private static final String TOOL_METRICS = "指标快照";
    private static final String TOOL_TRACE = "追踪快照";

    private final Map<String, String> snapshots;
    private final AtomicBoolean routeRead = new AtomicBoolean();
    private final AtomicBoolean instanceRead = new AtomicBoolean();
    /** 模型本次实际读过的工具，按首次调用顺序记录：解释里要能说清「读了哪几份证据」。 */
    private final CopyOnWriteArrayList<String> calls = new CopyOnWriteArrayList<>();

    public SnapshotTools(List<Evidence> evidence) {
        // 按取数地址建索引：工具的入参是来源地址，不是给人看的来源名。
        this.snapshots = evidence.stream().collect(Collectors.toMap(Evidence::rawReference, Evidence::summary));
    }

    @Tool(description = "读取当前请求路径匹配到的 Gateway 路由配置快照")
    public String routeSnapshot() {
        routeRead.set(true);
        return read(TOOL_ROUTE, "/api/routes", "路由数据不可用");
    }

    @Tool(description = "读取 Nameserver 注册实例及目标服务健康实例快照")
    public String instanceSnapshot() {
        instanceRead.set(true);
        return read(TOOL_INSTANCE, "/api/instances", "实例数据不可用");
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

    /** 模型是否已读取路由快照。 */
    public boolean hasReadRoute() {
        return routeRead.get();
    }

    /** 模型是否已读取实例快照。 */
    public boolean hasReadInstances() {
        return instanceRead.get();
    }

    /** 本次解读实际调用过的工具名（按首次调用顺序）；重复调用同一工具只记一次。 */
    public List<String> calledTools() {
        return List.copyOf(calls);
    }

    private String read(String tool, String reference, String fallback) {
        calls.addIfAbsent(tool);
        return snapshots.getOrDefault(reference, fallback);
    }
}
