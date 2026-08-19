package com.rover.nameserver.core.metrics;

import com.rover.common.constants.ManageApiPaths;
import com.rover.common.json.JsonCodec;
import com.rover.common.jvm.JvmMetricsCollector;
import com.rover.common.metrics.SecondCountRing;
import com.rover.common.constants.RoverComponent;
import com.rover.nameserver.core.model.InstanceRecord;
import com.rover.nameserver.core.registry.ServiceRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Author: Daylight
 * Description: Nameserver 指标统一数据源。注册/注销/心跳等生命周期事件用 LongAdder 无锁累加，
 * 最近事件写入固定容量环形缓冲（覆盖最旧），TCP 连接数用 AtomicInteger 实时增减。
 * 所有指标从本类派生，与 Gateway 的 MetricsRegistry 保持同样的数据自洽口径。
 */
public class NameserverMetricsRegistry {

    /** 最近事件缓冲容量：保留最近 200 条，内存固定。 */
    public static final int EVENT_CAPACITY = 200;

    /** 按秒环长度，和 Gateway 窗口上限对齐。 */
    public static final int RING_SECONDS = ManageApiPaths.LIVE_RANGE_5M;

    /** 启动纳秒时间（单调时钟），用于 uptime。 */
    private final long startNanos = System.nanoTime();

    /** 生命周期累计计数。 */
    private final LongAdder registerCount = new LongAdder();
    private final LongAdder unregisterCount = new LongAdder();
    private final LongAdder heartbeatCount = new LongAdder();
    private final LongAdder queryCount = new LongAdder();
    private final LongAdder subscribeCount = new LongAdder();
    private final LongAdder unsubscribeCount = new LongAdder();

    /** 健康检查动作计数。 */
    private final LongAdder expireCount = new LongAdder();
    private final LongAdder markUnhealthyCount = new LongAdder();

    /** 变更推送次数（按快照触发计，非按消息条数）。 */
    private final LongAdder pushCount = new LongAdder();

    /** 当前 TCP 客户端连接数（Netty 接入连接）。 */
    private final AtomicInteger activeConnections = new AtomicInteger();

    /** 按秒环：给控制台看「这一秒心跳/查询/推送了几次」。 */
    private final SecondCountRing heartbeatRing = new SecondCountRing(RING_SECONDS);
    private final SecondCountRing queryRing = new SecondCountRing(RING_SECONDS);
    private final SecondCountRing pushRing = new SecondCountRing(RING_SECONDS);
    private final SecondCountRing registerRing = new SecondCountRing(RING_SECONDS);

    /** 最近事件环形缓冲。 */
    private final RegistryEvent[] events = new RegistryEvent[EVENT_CAPACITY];
    private final AtomicInteger eventIndex = new AtomicInteger();
    private final Object eventLock = new Object();

    // ==================== 生命周期埋点 ====================

    public void register(String serviceName, String instanceId) {
        registerCount.increment();
        registerRing.increment();
        addEvent(EventType.REGISTER, serviceName, instanceId, "");
    }

    public void unregister(String serviceName, String instanceId) {
        unregisterCount.increment();
        addEvent(EventType.UNREGISTER, serviceName, instanceId, "");
    }

    public void heartbeat(String serviceName, String instanceId) {
        heartbeatCount.increment();
        heartbeatRing.increment();
    }

    public void query(String serviceName) {
        queryCount.increment();
        queryRing.increment();
    }

    public void subscribe(String serviceName) {
        subscribeCount.increment();
    }

    public void unsubscribe(String serviceName) {
        unsubscribeCount.increment();
    }

    public void expireEvict(String serviceName, String instanceId, long idleMillis, long expireMillis) {
        expireCount.increment();
        addEvent(EventType.EXPIRE_EVICT, serviceName, instanceId,
                "idle=" + idleMillis + "ms, expire=" + expireMillis + "ms");
    }

    public void markUnhealthy(String serviceName, String instanceId, long idleMillis) {
        markUnhealthyCount.increment();
        addEvent(EventType.MARK_UNHEALTHY, serviceName, instanceId, "idle=" + idleMillis + "ms");
    }

    public void push(String serviceName, int subscriberCount) {
        pushCount.increment();
        pushRing.increment();
        addEvent(EventType.PUSH, serviceName, "", "subscribers=" + subscriberCount);
    }

    // ==================== TCP 连接 ====================

    public void connectionOpened() {
        activeConnections.incrementAndGet();
    }

    public void connectionClosed() {
        activeConnections.updateAndGet(v -> Math.max(0, v - 1));
    }

    // ==================== 事件缓冲 ====================

    private void addEvent(EventType type, String serviceName, String instanceId, String detail) {
        RegistryEvent event = new RegistryEvent();
        event.timestampMillis = System.currentTimeMillis();
        event.type = type.name();
        event.serviceName = serviceName == null ? "" : serviceName;
        event.instanceId = instanceId == null ? "" : instanceId;
        event.detail = detail == null ? "" : detail;
        int index;
        synchronized (eventLock) {
            index = eventIndex.getAndUpdate(v -> (v + 1) % EVENT_CAPACITY);
            events[index] = event;
        }
    }

    // ==================== 对外快照 ====================

    /** 组装 /_manage/metrics 的 JSON 内容。 */
    public String snapshotJson(ServiceRegistry registry) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("component", RoverComponent.NAMESERVER.id());
        root.put("uptimeSeconds", (System.nanoTime() - startNanos) / 1_000_000_000L);
        root.put("jvm", JvmMetricsCollector.collect());

        // 注册概况：服务数、实例数、健康/不健康
        Map<String, Object> registryView = new LinkedHashMap<>();
        List<InstanceRecord> records = registry.listAllRecords();
        Map<String, Integer> serviceCounter = new LinkedHashMap<>();
        int healthy = 0;
        for (InstanceRecord record : records) {
            serviceCounter.merge(record.getInstance().getServiceName(), 1, Integer::sum);
            if (record.getInstance().isHealthy()) {
                healthy++;
            }
        }
        registryView.put("serviceCount", serviceCounter.size());
        registryView.put("instanceCount", records.size());
        registryView.put("healthyInstances", healthy);
        registryView.put("unhealthyInstances", Math.max(0, records.size() - healthy));
        root.put("registry", registryView);
        root.put("instant", instantMap(System.currentTimeMillis() / 1000));

        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("register", registerCount.sum());
        counters.put("unregister", unregisterCount.sum());
        counters.put("heartbeat", heartbeatCount.sum());
        counters.put("query", queryCount.sum());
        counters.put("subscribe", subscribeCount.sum());
        counters.put("unsubscribe", unsubscribeCount.sum());
        counters.put("expireEvict", expireCount.sum());
        counters.put("markUnhealthy", markUnhealthyCount.sum());
        counters.put("push", pushCount.sum());
        root.put("counters", counters);

        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("activeConnections", activeConnections.get());
        root.put("resources", resources);
        return JsonCodec.toJson(root);
    }

    /**
     * 轻量实时快照：注册概况 + 上一秒操作次数 + JVM，给 Admin 1 秒轮询。
     * 不带事件列表，不扫全量 counters 以外的重活。
     */
    public String liveJson(ServiceRegistry registry, int rangeSeconds) {
        long nowSecond = System.currentTimeMillis() / 1000;
        int range = rangeSeconds <= ManageApiPaths.LIVE_RANGE_1M
                ? ManageApiPaths.LIVE_RANGE_1M
                : ManageApiPaths.LIVE_RANGE_5M;

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("component", RoverComponent.NAMESERVER.id());
        root.put("serverTimeMillis", System.currentTimeMillis());
        root.put("rangeSeconds", range);
        root.put("jvm", JvmMetricsCollector.collect());
        root.put("registry", registryView(registry));
        root.put("instant", instantMap(nowSecond));
        root.put("opsSeries", opsSeries(nowSecond, range));

        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("activeConnections", activeConnections.get());
        root.put("resources", resources);
        return JsonCodec.toJson(root);
    }

    private Map<String, Object> instantMap(long nowSecond) {
        Map<String, Object> instant = new LinkedHashMap<>();
        instant.put("heartbeat", heartbeatRing.countAt(nowSecond - 1));
        instant.put("query", queryRing.countAt(nowSecond - 1));
        instant.put("push", pushRing.countAt(nowSecond - 1));
        instant.put("register", registerRing.countAt(nowSecond - 1));
        instant.put("currentHeartbeat", heartbeatRing.countAt(nowSecond));
        instant.put("currentQuery", queryRing.countAt(nowSecond));
        instant.put("currentPush", pushRing.countAt(nowSecond));
        return instant;
    }

    private Map<String, Object> registryView(ServiceRegistry registry) {
        Map<String, Object> registryView = new LinkedHashMap<>();
        List<InstanceRecord> records = registry.listAllRecords();
        Map<String, Integer> serviceCounter = new LinkedHashMap<>();
        int healthy = 0;
        for (InstanceRecord record : records) {
            serviceCounter.merge(record.getInstance().getServiceName(), 1, Integer::sum);
            if (record.getInstance().isHealthy()) {
                healthy++;
            }
        }
        registryView.put("serviceCount", serviceCounter.size());
        registryView.put("instanceCount", records.size());
        registryView.put("healthyInstances", healthy);
        registryView.put("unhealthyInstances", Math.max(0, records.size() - healthy));
        return registryView;
    }

    private List<Map<String, Object>> opsSeries(long nowSecond, int window) {
        List<Map<String, Object>> series = new ArrayList<>(window);
        for (long second = nowSecond - window + 1; second <= nowSecond; second++) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("second", second);
            point.put("heartbeat", heartbeatRing.countAt(second));
            point.put("push", pushRing.countAt(second));
            point.put("query", queryRing.countAt(second));
            series.add(point);
        }
        return series;
    }

    /** 组装 /_manage/events 的 JSON 内容（最近事件列表，倒序：最新在前）。 */
    public String eventsJson() {
        List<Map<String, Object>> rows = new ArrayList<>();
        RegistryEvent[] copy = new RegistryEvent[EVENT_CAPACITY];
        synchronized (eventLock) {
            System.arraycopy(events, 0, copy, 0, EVENT_CAPACITY);
        }
        // 倒序遍历：最近写入的 index 在最后，先输出它；跳过空槽
        int start = eventIndex.get();
        for (int step = 0; step < EVENT_CAPACITY; step++) {
            int index = Math.floorMod(start - step, EVENT_CAPACITY);
            RegistryEvent event = copy[index];
            if (event == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("timestampMillis", event.timestampMillis);
            row.put("type", event.type);
            row.put("serviceName", event.serviceName);
            row.put("instanceId", event.instanceId);
            row.put("detail", event.detail);
            rows.add(row);
        }
        return JsonCodec.toJson(rows);
    }

    /** 最近事件类型。 */
    public enum EventType {
        REGISTER, UNREGISTER, EXPIRE_EVICT, MARK_UNHEALTHY, PUSH
    }

    /** 单条事件记录。 */
    private static final class RegistryEvent {
        long timestampMillis;
        String type;
        String serviceName;
        String instanceId;
        String detail;
    }
}
