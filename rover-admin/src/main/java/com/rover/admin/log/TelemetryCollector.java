package com.rover.admin.log;

import com.fasterxml.jackson.databind.JsonNode;
import com.rover.admin.config.AdminProperties;
import com.rover.admin.service.AdminConfigService;
import com.rover.common.concurrent.PeriodicTask;
import com.rover.common.constants.ManageApiPaths;
import com.rover.common.constants.RoverComponent;
import com.rover.common.json.JsonCodec;
import com.rover.common.log.Record;
import com.rover.common.log.RecordStore;
import com.rover.common.log.RecordType;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * 遥测采集器：周期拉取 Gateway / Nameserver 管理口，把可观测数据「蒸馏」后写进落盘记录库，
 * 作为后续开放给 Agent / LLM 工具做历史证据检索的数据源。
 *
 * <p>采集四类，粒度面向 LLM 消费做了取舍：
 * <ul>
 *   <li>{@link RecordType#METRICS_SAMPLE}：每组件每周期一条聚合指标快照（时间序列趋势）。</li>
 *   <li>{@link RecordType#INSTANCE_EVENT}：组件可达性 + 单实例健康「状态翻转」，只在变化时记一条
 *       （问「何时宕机 / 哪个实例掉线 / 何时注册或移除」）。</li>
 *   <li>{@link RecordType#REQUEST_TRACE}：慢请求（slow）+ 错误请求（statusCode≥500）链路，traceId 去重。</li>
 * </ul>
 * 原始心跳、逐请求日志不入库：量大、对 LLM 是噪声，且正常态可用指标采样的时间线覆盖。
 *
 * <p>自身用 {@link PeriodicTask} 固定延迟调度，单次失败不中断；实现 {@link AutoCloseable} 随容器关闭。
 */
@Slf4j
public class TelemetryCollector implements AutoCloseable {

    /** 已采链路 traceId 去重集合上限：超出后淘汰最旧，代价是极端旧链路可能重写一次（可接受，遥测 best-effort）。 */
    private static final int MAX_SEEN_TRACE_IDS = 10_000;

    private final AdminConfigService service;
    private final RecordStore store;
    private final PeriodicTask task;
    /** 各组件上次可达性，用于只在状态翻转时记 INSTANCE_EVENT。 */
    private final Map<String, Boolean> lastReachable = new ConcurrentHashMap<>();
    /** 单实例上次健康状态（key=instanceId），用于只记注册/移除/上/下翻转。 */
    private final Map<String, InstanceState> knownInstances = new ConcurrentHashMap<>();
    /** 实例健康基线是否已建立：首轮只灌底、不记事件，避免启动时对既有实例刷屏。 */
    private final AtomicBoolean instanceBaselined = new AtomicBoolean(false);
    /** 已采链路的 traceId 去重集合（有界）。 */
    private final Set<String> seenTraceIds = new LinkedHashSet<>();

    public TelemetryCollector(AdminConfigService service, RecordStore store, AdminProperties props) {
        this.service = service;
        this.store = store;
        this.task = new PeriodicTask("rover-telemetry-collector");
        long intervalMs = Math.max(5, props.getLogCollectIntervalSeconds()) * 1000L;
        // 初始延迟一个周期，等其余 bean 就绪再首采，避免启动窗口内的噪声
        this.task.start(this::collect, intervalMs, intervalMs);
    }

    /** 单次采集：组件健康翻转 → 实例健康翻转 → 指标采样 → 慢/错误链路。包可见，便于测试直接驱动。 */
    void collect() {
        try {
            collectHealth();
            collectInstanceHealth();
            collectMetrics();
            collectTraces();
        } catch (Throwable ex) {
            // PeriodicTask 已兜底，这里再兜一层，保证单类失败不影响其余采集
            log.warn("遥测采集单轮异常", ex);
        }
    }

    /** 组件级（Gateway / Nameserver 可达性）状态翻转。 */
    private void collectHealth() {
        Map<String, Object> status = service.loadStatus();
        for (String component : List.of(RoverComponent.GATEWAY.id(), RoverComponent.NAMESERVER.id())) {
            Object raw = status == null ? null : status.get(component);
            if (!(raw instanceof Map<?, ?> m)) {
                continue;
            }
            boolean reachable = Boolean.TRUE.equals(m.get("reachable"));
            Boolean prev = lastReachable.put(component, reachable);
            if (prev != null && prev.booleanValue() != reachable) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("kind", "component");
                payload.put("component", component);
                payload.put("event", reachable ? "up" : "down");
                payload.put("at", System.currentTimeMillis());
                store.log(Record.of(RecordType.INSTANCE_EVENT, component, JsonCodec.toJson(payload)));
                log.info("组件状态翻转: {} -> {}", component, reachable ? "up" : "down");
            }
        }
    }

    /** 单实例级健康翻转：注册/移除/上/下，只在变化时记一条。 */
    private void collectInstanceHealth() {
        List<Map<String, Object>> instances;
        try {
            instances = service.listInstances();
        } catch (Exception ex) {
            log.debug("拉取实例列表失败: {}", ex.getMessage());
            return;
        }
        if (instances == null) {
            return;
        }
        boolean first = !instanceBaselined.get();
        Map<String, Map<String, Object>> current = new LinkedHashMap<>();
        for (Map<String, Object> inst : instances) {
            String id = str(inst.get("instanceId"));
            if (id.isEmpty()) {
                continue;
            }
            current.put(id, inst);
        }
        for (Map.Entry<String, Map<String, Object>> e : current.entrySet()) {
            String id = e.getKey();
            Map<String, Object> inst = e.getValue();
            boolean healthy = Boolean.TRUE.equals(inst.get("healthy"));
            InstanceState prev = knownInstances.put(id, new InstanceState(str(inst.get("serviceName")), healthy));
            if (prev == null) {
                if (!first) {
                    emitInstanceEvent(inst, "register");        // 基线后新出现的实例
                }
            } else if (prev.healthy() != healthy) {
                emitInstanceEvent(inst, healthy ? "up" : "down");
            }
        }
        // 基线后从注册表消失的实例 → removed
        if (!first) {
            for (Iterator<Map.Entry<String, InstanceState>> it = knownInstances.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, InstanceState> e = it.next();
                if (!current.containsKey(e.getKey())) {
                    emitInstanceRemoved(e.getKey(), e.getValue());
                    it.remove();
                }
            }
        }
        instanceBaselined.set(true);
    }

    private void emitInstanceEvent(Map<String, Object> inst, String event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "instance");
        payload.put("serviceName", str(inst.get("serviceName")));
        payload.put("instanceId", str(inst.get("instanceId")));
        payload.put("host", str(inst.get("host")));
        payload.put("port", inst.get("port"));
        payload.put("event", event);
        payload.put("at", System.currentTimeMillis());
        store.log(Record.of(RecordType.INSTANCE_EVENT, str(inst.get("serviceName")), JsonCodec.toJson(payload)));
    }

    private void emitInstanceRemoved(String instanceId, InstanceState state) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "instance");
        payload.put("serviceName", state.serviceName());
        payload.put("instanceId", instanceId);
        payload.put("event", "removed");
        payload.put("at", System.currentTimeMillis());
        store.log(Record.of(RecordType.INSTANCE_EVENT, state.serviceName(), JsonCodec.toJson(payload)));
    }

    private void collectMetrics() {
        sample(RoverComponent.GATEWAY.id(), () -> service.loadMetrics());
        sample(RoverComponent.NAMESERVER.id(), () -> service.loadNameserverMetrics());
    }

    private void sample(String component, Pullable pull) {
        try {
            JsonNode node = pull.pull();
            store.log(Record.of(RecordType.METRICS_SAMPLE, component, node.toString()));
        } catch (Exception ex) {
            // 组件不可达：仍记一条不可达采样，让 LLM 能看到「这段时间没数据」而不是静默空档
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("reachable", false);
            payload.put("error", String.valueOf(ex.getMessage()));
            store.log(Record.of(RecordType.METRICS_SAMPLE, component, JsonCodec.toJson(payload)));
        }
    }

    /** 拉取慢 + 错误两条链路，按 traceId 去重后写入（慢与错误可能重叠，同一 trace 只写一次）。 */
    private void collectTraces() {
        List<JsonNode> rows = new ArrayList<>();
        for (String filter : new String[]{ManageApiPaths.PARAM_SLOW, ManageApiPaths.PARAM_ERROR}) {
            JsonNode resp;
            try {
                resp = service.loadTraces(Map.of(filter, "true"));
            } catch (Exception ex) {
                log.debug("拉取链路失败 filter={}: {}", filter, ex.getMessage());
                continue;
            }
            JsonNode arr = resp == null ? null : resp.get("traces");
            if (arr != null && arr.isArray()) {
                for (JsonNode row : arr) {
                    rows.add(row);
                }
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        // traceId 去重（slow 与 error 两路可能重叠）
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            byId.putIfAbsent(traceId(row), row);
        }
        int written = 0;
        for (Map.Entry<String, JsonNode> e : byId.entrySet()) {
            if (!markSeen(e.getKey())) {
                continue;                       // 之前已采过
            }
            JsonNode row = e.getValue();
            String target = row.path("routeId").asText("");
            if (target.isBlank()) {
                target = row.path("path").asText("");
            }
            store.log(Record.of(RecordType.REQUEST_TRACE, target, row.toString()));
            written++;
        }
        if (written > 0) {
            log.debug("采样链路 {} 条", written);
        }
    }

    /** trace 的稳定去重键：优先 traceId，缺失时用 path@startMillis 兜底。 */
    private static String traceId(JsonNode row) {
        String id = row.path("traceId").asText("");
        if (!id.isBlank()) {
            return id;
        }
        return row.path("path").asText("") + "@" + row.path("startMillis").asLong(0L);
    }

    /** 有界去重：首次见到返回 true；已见返回 false；超上限淘汰最旧。 */
    private boolean markSeen(String id) {
        if (!seenTraceIds.add(id)) {
            return false;
        }
        if (seenTraceIds.size() > MAX_SEEN_TRACE_IDS) {
            Iterator<String> it = seenTraceIds.iterator();
            it.next();
            it.remove();
        }
        return true;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    @Override
    public void close() {
        task.stop();
    }

    /** 单实例健康状态快照（key=instanceId）。 */
    private record InstanceState(String serviceName, boolean healthy) {
    }

    /** 拉取动作的极简抽象，便于 sample() 复用与测试。 */
    @FunctionalInterface
    private interface Pullable {
        JsonNode pull();
    }
}
