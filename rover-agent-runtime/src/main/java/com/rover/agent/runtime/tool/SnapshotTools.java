package com.rover.agent.runtime.tool;

import com.rover.agent.core.model.Evidence;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.springframework.ai.tool.annotation.Tool;

/** 模型只能读取本次调查已采集的快照，不能访问管理接口，也不能产生任何写操作。 */
public final class SnapshotTools {

    private final Map<String, String> snapshots;
    private final AtomicBoolean routeRead = new AtomicBoolean();
    private final AtomicBoolean instanceRead = new AtomicBoolean();

    public SnapshotTools(List<Evidence> evidence) {
        this.snapshots = evidence.stream().collect(Collectors.toMap(Evidence::source, Evidence::detail));
    }

    @Tool(description = "读取当前请求路径匹配到的 Gateway 路由配置快照")
    public String routeSnapshot() {
        routeRead.set(true);
        return snapshots.getOrDefault("/api/routes", "路由数据不可用");
    }

    @Tool(description = "读取 Nameserver 注册实例及目标服务健康实例快照")
    public String instanceSnapshot() {
        instanceRead.set(true);
        return snapshots.getOrDefault("/api/instances", "实例数据不可用");
    }

    @Tool(description = "读取最近一分钟 Gateway 全局流量和拒绝计数快照；不是单一路由指标")
    public String metricsSnapshot() {
        return snapshots.getOrDefault("/api/live?range=60", "实时指标不可用");
    }

    @Tool(description = "读取当前请求路径的 Gateway 抽样追踪快照")
    public String traceSnapshot() {
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
}