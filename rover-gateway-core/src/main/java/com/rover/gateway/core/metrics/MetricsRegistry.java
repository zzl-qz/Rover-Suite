package com.rover.gateway.core.metrics;

import com.rover.common.constants.HttpConstants;
import com.rover.common.constants.ManageApiPaths;
import com.rover.gateway.core.config.GatewayDefaults;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Author: Daylight
 * Description: 网关指标统一数据源。累计值用 LongAdder 无锁累加；窗口按秒聚合，
 * 环形数组实现（内存固定）；耗时百分位基于窗口内原始样本（蓄水池采样）计算。
 * 所有派生指标（QPS/平均/P95/路由维度）都从本类派生，保证数据自洽。
 */
public class MetricsRegistry {

    /** 环形数组固定长度（秒），即窗口上限 5 分钟。 */
    public static final int RING_SECONDS = GatewayDefaults.METRICS_WINDOW_SECONDS;

    /** 每秒耗时样本蓄水池容量，够算 P99 且内存极小。 */
    static final int RESERVOIR_SIZE = 64;

    /** 路由维度中"未匹配任何路由"的归类键。 */
    public static final String UNMATCHED_ROUTE = "__unmatched__";

    /** 采集配置，支持热更新。 */
    final MetricsSettings settings;

    /** 启动纳秒时间（单调时钟），用于 uptime 与耗时统计。 */
    final long startNanos = System.nanoTime();

    /** 全局累计请求数。 */
    final LongAdder totalRequests = new LongAdder();

    /** 全局状态码累计（四类互斥且穷尽，加和恒等于 totalRequests）。 */
    final LongAdder status2xx = new LongAdder();
    final LongAdder status3xx = new LongAdder();
    final LongAdder status4xx = new LongAdder();
    final LongAdder status5xx = new LongAdder();

    /** 网关自身错误累计（按类型）。 */
    final LongAdder errRouteUnmatched = new LongAdder();
    final LongAdder errProxyTimeout = new LongAdder();
    final LongAdder errUpstreamConnect = new LongAdder();

    /** 拒绝计数：只在 503 路径加，metrics.enabled=false 也记，方便压测对原因。 */
    final LongAdder rejectInflightLimit = new LongAdder();
    final LongAdder rejectNoUpstream = new LongAdder();
    final LongAdder rejectCircuitOpen = new LongAdder();

    /** 因连接失败换台次数。关指标也记。 */
    final LongAdder retryConnect = new LongAdder();

    /** 活跃连接数（Netty 接入连接，只增只减不随窗口过期）。 */
    final AtomicInteger activeConnections = new AtomicInteger();

    /** 在途请求数（正在处理中未返回的请求）。 */
    final AtomicInteger inflightRequests = new AtomicInteger();

    /** 全局按秒环形窗口。 */
    final TimeRing globalRing = new TimeRing(RING_SECONDS);

    /** 路由维度统计，键为 routeId（未匹配归 UNMATCHED_ROUTE）。 */
    final ConcurrentHashMap<String, RouteMetrics> routes = new ConcurrentHashMap<>();

    /** 上游维度统计，键为 host:port，记录上游响应时间与连接失败/超时。 */
    final ConcurrentHashMap<String, UpstreamMetrics> upstreams = new ConcurrentHashMap<>();

    /** 在途上游请求数来源，由 HttpProxyClient 提供（近似连接池使用情况）。 */
    volatile IntSupplier upstreamInFlightSupplier = () -> 0;
    volatile Supplier<Map<String, Object>> discoveryStatusSupplier = () -> Map.of("supported", false);

    /** live 路由 Top 缓存：降低每秒排序成本。 */
    volatile List<Map<String, Object>> liveTopRoutesCache = List.of();
    final AtomicLong liveTopRoutesCachedAtMillis = new AtomicLong(0);

    public MetricsRegistry(MetricsSettings settings) {
        this.settings = settings == null ? new MetricsSettings() : settings;
    }

    /** 当前配置快照（供端点展示）。 */
    public MetricsSettings getSettings() {
        return settings;
    }

    /** 注入在途上游请求数来源（HttpProxyClient）。 */
    public void setUpstreamInFlightSupplier(IntSupplier supplier) {
        this.upstreamInFlightSupplier = supplier == null ? () -> 0 : supplier;
    }

    /** 注入服务发现状态来源，供 metrics 和 Prometheus 导出。 */
    public void setDiscoveryStatusSupplier(Supplier<Map<String, Object>> supplier) {
        this.discoveryStatusSupplier = supplier == null ? () -> Map.of("supported", false) : supplier;
    }

    /**
     * 记录一次请求（无上游信息，用于路由未匹配/无可用上游等场景）。
     * 由 MetricsFilter 在请求结束时调用，内部绝不允许抛异常影响主链路。
     */
    public void record(String routeId, int statusCode, long costMillis) {
        record(routeId, statusCode, costMillis, null, 0, false, false);
    }

    /**
     * 记录一次请求（含上游维度）。由 MetricsFilter 在请求结束时调用。
     *
     * @param routeId            命中的路由 id，未匹配传 null
     * @param statusCode         最终响应状态码
     * @param costMillis         请求总耗时（毫秒）
     * @param upstreamHostPort   命中的上游实例 host:port，未转发传 null
     * @param upstreamCostMillis 上游往返耗时（毫秒）
     * @param connectFail        上游是否连接失败
     * @param timeout            上游是否超时
     */
    public void record(
            String routeId,
            int statusCode,
            long costMillis,
            String upstreamHostPort,
            long upstreamCostMillis,
            boolean connectFail,
            boolean timeout) {
        if (!settings.isEnabled()) {
            return;
        }
        long cost = Math.max(0, costMillis);
        long epochSecond = System.currentTimeMillis() / 1000;

        totalRequests.increment();
        addStatus(statusCode);

        String routeKey = routeId == null || routeId.isBlank() ? UNMATCHED_ROUTE : routeId;
        boolean gatewayError = routeId == null || routeId.isBlank();
        if (statusCode == 504) {
            errProxyTimeout.increment();
            gatewayError = true;
        } else if (statusCode == 502) {
            errUpstreamConnect.increment();
            gatewayError = true;
        }
        if (routeId == null || routeId.isBlank()) {
            errRouteUnmatched.increment();
        }

        globalRing.record(epochSecond, cost, gatewayError, statusCode);
        RouteMetrics routeMetrics = routes.computeIfAbsent(routeKey, key -> new RouteMetrics());
        routeMetrics.record(statusCode, epochSecond, cost);

        // 上游维度 + 路由实例分布：只有真实转发到上游才统计
        if (upstreamHostPort != null && !upstreamHostPort.isBlank()) {
            upstreams.computeIfAbsent(upstreamHostPort, key -> new UpstreamMetrics())
                    .record(epochSecond, upstreamCostMillis, connectFail, timeout);
            routeMetrics.recordInstance(upstreamHostPort);
        }
    }

    /** 状态码归类累加，四类互斥穷尽。 */
    private void addStatus(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            status2xx.increment();
        } else if (statusCode >= 300 && statusCode < 400) {
            status3xx.increment();
        } else if (statusCode >= 400 && statusCode < 500) {
            status4xx.increment();
        } else {
            // 1xx/5xx 及以上都归 5xx 桶，保证四桶穷尽、加和恒等
            status5xx.increment();
        }
    }

    /** 记录一次连接建立（Netty channelActive）。 */
    public void connectionOpened() {
        if (!settings.isEnabled()) {
            return;
        }
        activeConnections.incrementAndGet();
    }

    /** 记录一次连接关闭（Netty channelInactive），夹到 0 防止并发下负数。 */
    public void connectionClosed() {
        if (!settings.isEnabled()) {
            return;
        }
        activeConnections.updateAndGet(v -> Math.max(0, v - 1));
    }

    /** 记录一个请求开始处理（在途 +1）。 */
    public void requestStarted() {
        if (!settings.isEnabled()) {
            return;
        }
        inflightRequests.incrementAndGet();
    }

    /** 记录一个请求处理结束（在途 -1），夹到 0。 */
    public void requestFinished() {
        if (!settings.isEnabled()) {
            return;
        }
        inflightRequests.updateAndGet(v -> Math.max(0, v - 1));
    }

    /** 记一次 503 原因。只在拒绝路径调用，关指标也加。 */
    public void recordReject(String reason) {
        if (HttpConstants.REJECT_INFLIGHT_LIMIT.equals(reason)) {
            rejectInflightLimit.increment();
        } else if (HttpConstants.REJECT_NO_UPSTREAM.equals(reason)) {
            rejectNoUpstream.increment();
        } else if (HttpConstants.REJECT_CIRCUIT_OPEN.equals(reason)) {
            rejectCircuitOpen.increment();
        }
    }

    /** 记一次「连不上换台」。 */
    public void recordRetryConnect() {
        retryConnect.increment();
    }

    private final MetricsExporter exporter = new MetricsExporter(this);

    /** 组装 /_manage/metrics 的 JSON 内容。 */
    public String snapshotJson() {
        return exporter.snapshotJson();
    }

    /**
     * 轻量实时快照：给 Admin 1 秒轮询用。
     * 相对全量 metrics：不算 p99、不带上游 Top、路由 Top 5 秒缓存且不算路由 p95。
     */
    public String liveJson(int rangeSeconds) {
        return exporter.liveJson(rangeSeconds);
    }

    /** 自洽性自检：校验各加和关系，返回 JSON。 */
    public String selfcheckJson() {
        return exporter.selfcheckJson();
    }

    /**
     * Prometheus 文本格式导出（不内置依赖，只是格式转换）。
     * 供有需要的用户对接外部 Grafana/Prometheus，与 snapshotJson 同一数据源派生。
     */
    public String prometheusText() {
        return exporter.prometheusText();
    }

    static int clampRange(int rangeSeconds) {
        if (rangeSeconds <= ManageApiPaths.LIVE_RANGE_1M) {
            return ManageApiPaths.LIVE_RANGE_1M;
        }
        return ManageApiPaths.LIVE_RANGE_5M;
    }

    /** 合并窗口内样本算百分位，基于原始样本而非平均的平均。 */
    static long percentile(long[] samples, double ratio) {
        if (samples.length == 0) {
            return 0;
        }
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(ratio * sorted.length) - 1;
        index = Math.max(0, Math.min(sorted.length - 1, index));
        return sorted[index];
    }

    /** 保留两位小数，路由/上游 snapshot 与导出共用。 */
    static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * 按秒环形窗口。槽位存"单调累加器 + 基线"，读时用 delta 得出该秒数据，
     * 清零只发生在秒切换瞬间且持槽位锁，正常请求路径无锁竞争。
     */
    static final class TimeRing {
        private final Slot[] slots;

        TimeRing(int seconds) {
            this.slots = new Slot[seconds];
            for (int i = 0; i < seconds; i++) {
                slots[i] = new Slot();
            }
        }

        /** 记录一个样本到对应秒槽位。 */
        void record(long epochSecond, long costMillis, boolean error) {
            record(epochSecond, costMillis, error, 200);
        }

        /** 记录一个样本，并按状态码归进近窗四桶。 */
        void record(long epochSecond, long costMillis, boolean error, int statusCode) {
            Slot slot = slots[(int) Math.floorMod(epochSecond, slots.length)];
            if (slot.stamp != epochSecond) {
                synchronized (slot) {
                    if (slot.stamp != epochSecond) {
                        // 先取基线再发布新 stamp，保证看到新 stamp 的线程必看到新基线
                        slot.baseCount = slot.count.sum();
                        slot.baseSum = slot.sumMillis.sum();
                        slot.baseErrors = slot.errors.sum();
                        slot.base2xx = slot.s2.sum();
                        slot.base3xx = slot.s3.sum();
                        slot.base4xx = slot.s4.sum();
                        slot.base5xx = slot.s5.sum();
                        slot.maxMillis.set(0);
                        slot.sampleSize.set(0);
                        slot.sampleCount.set(0);
                        slot.stamp = epochSecond;
                    }
                }
            }
            slot.count.increment();
            slot.sumMillis.add(costMillis);
            if (error) {
                slot.errors.increment();
            }
            addStatusBucket(slot, statusCode);
            long currentMax = slot.maxMillis.get();
            while (costMillis > currentMax) {
                if (slot.maxMillis.compareAndSet(currentMax, costMillis)) {
                    break;
                }
                currentMax = slot.maxMillis.get();
            }
            reservoirAdd(slot, costMillis);
        }

        private static void addStatusBucket(Slot slot, int statusCode) {
            if (statusCode >= 200 && statusCode < 300) {
                slot.s2.increment();
            } else if (statusCode >= 300 && statusCode < 400) {
                slot.s3.increment();
            } else if (statusCode >= 400 && statusCode < 500) {
                slot.s4.increment();
            } else {
                slot.s5.increment();
            }
        }

        /** 蓄水池采样（Algorithm R），固定内存保留代表性耗时样本。 */
        private void reservoirAdd(Slot slot, long costMillis) {
            long seen = slot.sampleCount.incrementAndGet();
            if (seen <= RESERVOIR_SIZE) {
                slot.samples[(int) (seen - 1)] = costMillis;
                slot.sampleSize.incrementAndGet();
            } else {
                long replaceIndex = ThreadLocalRandom.current().nextLong(seen);
                if (replaceIndex < RESERVOIR_SIZE) {
                    slot.samples[(int) replaceIndex] = costMillis;
                }
            }
        }

        /** 某一秒的请求数（过期秒返回 0）。 */
        long countAt(long epochSecond) {
            Slot slot = slots[(int) Math.floorMod(epochSecond, slots.length)];
            return slot.stamp == epochSecond ? slot.count.sum() - slot.baseCount : 0;
        }

        /** 汇总窗口内各秒数据。 */
        WindowView view(long nowSecond, int windowSeconds) {
            WindowView view = new WindowView();
            List<Long> sampleList = new ArrayList<>();
            long actualSeconds = 0;
            for (long second = nowSecond - windowSeconds + 1; second <= nowSecond; second++) {
                Slot slot = slots[(int) Math.floorMod(second, slots.length)];
                if (slot.stamp != second) {
                    continue;
                }
                long count = slot.count.sum() - slot.baseCount;
                long sum = slot.sumMillis.sum() - slot.baseSum;
                long errs = slot.errors.sum() - slot.baseErrors;
                long s2 = slot.s2.sum() - slot.base2xx;
                long s3 = slot.s3.sum() - slot.base3xx;
                long s4 = slot.s4.sum() - slot.base4xx;
                long s5 = slot.s5.sum() - slot.base5xx;
                // 秒切换瞬间的极小并发误差可能导致 delta 为负，夹到 0 保证展示自洽
                count = Math.max(0, count);
                sum = Math.max(0, sum);
                errs = Math.max(0, errs);
                view.requestCount += count;
                view.sumMillis += sum;
                view.errorCount += errs;
                view.status2xx += Math.max(0, s2);
                view.status3xx += Math.max(0, s3);
                view.status4xx += Math.max(0, s4);
                view.status5xx += Math.max(0, s5);
                view.maxMillis = Math.max(view.maxMillis, slot.maxMillis.get());
                actualSeconds++;
                int size = Math.min(slot.sampleSize.get(), RESERVOIR_SIZE);
                for (int i = 0; i < size; i++) {
                    sampleList.add(slot.samples[i]);
                }
            }
            view.actualSeconds = Math.max(1, actualSeconds);
            view.samples = toPrimitive(sampleList);
            return view;
        }

        private static long[] toPrimitive(List<Long> list) {
            long[] array = new long[list.size()];
            for (int i = 0; i < list.size(); i++) {
                array[i] = list.get(i);
            }
            return array;
        }

        /** 单个秒槽位。 */
        static final class Slot {
            volatile long stamp;
            volatile long baseCount;
            volatile long baseSum;
            volatile long baseErrors;
            volatile long base2xx;
            volatile long base3xx;
            volatile long base4xx;
            volatile long base5xx;
            final LongAdder count = new LongAdder();
            final LongAdder sumMillis = new LongAdder();
            final LongAdder errors = new LongAdder();
            final LongAdder s2 = new LongAdder();
            final LongAdder s3 = new LongAdder();
            final LongAdder s4 = new LongAdder();
            final LongAdder s5 = new LongAdder();
            final AtomicLong maxMillis = new AtomicLong();
            final long[] samples = new long[RESERVOIR_SIZE];
            final AtomicInteger sampleSize = new AtomicInteger();
            final AtomicLong sampleCount = new AtomicLong();
        }

        /** 窗口汇总结果。 */
        static final class WindowView {
            long requestCount;
            long sumMillis;
            long errorCount;
            long maxMillis;
            long actualSeconds;
            long status2xx;
            long status3xx;
            long status4xx;
            long status5xx;
            long[] samples = new long[0];
        }
    }

    /** 路由维度统计：累计 + 状态码 + 按秒窗口 + 上游实例分布。 */
    static final class RouteMetrics {
        final LongAdder total = new LongAdder();
        final LongAdder s2 = new LongAdder();
        final LongAdder s3 = new LongAdder();
        final LongAdder s4 = new LongAdder();
        final LongAdder s5 = new LongAdder();
        final TimeRing ring = new TimeRing(RING_SECONDS);
        /** 命中的上游实例分布，键为 host:port，用于观察负载均衡是否均匀。 */
        final ConcurrentHashMap<String, LongAdder> instanceCounts = new ConcurrentHashMap<>();

        void record(int statusCode, long epochSecond, long costMillis) {
            total.increment();
            if (statusCode >= 200 && statusCode < 300) {
                s2.increment();
            } else if (statusCode >= 300 && statusCode < 400) {
                s3.increment();
            } else if (statusCode >= 400 && statusCode < 500) {
                s4.increment();
            } else {
                s5.increment();
            }
            ring.record(epochSecond, costMillis, statusCode >= 500, statusCode);
        }

        void recordInstance(String hostPort) {
            instanceCounts.computeIfAbsent(hostPort, key -> new LongAdder()).increment();
        }

        boolean statusSumEqualsTotal() {
            return s2.sum() + s3.sum() + s4.sum() + s5.sum() == total.sum();
        }

        long windowRequestCount(long nowSecond, int windowSeconds) {
            TimeRing.WindowView view = ring.view(nowSecond, windowSeconds);
            return view.requestCount;
        }

        Map<String, Object> snapshot(String routeId, long nowSecond, int windowSeconds) {
            TimeRing.WindowView view = ring.view(nowSecond, windowSeconds);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("routeId", routeId);
            row.put("requests", total.sum());
            row.put("windowRequests", view.requestCount);
            row.put("qps", round2(view.requestCount / (double) view.actualSeconds));
            row.put("lastSecondRequests", ring.countAt(nowSecond - 1));
            // 错误率口径：仅 5xx 计为服务错误，分母为窗口请求数
            row.put("errorRate", view.requestCount == 0
                    ? 0 : round2(view.errorCount / (double) view.requestCount));
            row.put("avgMillis", view.requestCount == 0 ? 0 : round2(view.sumMillis / (double) view.requestCount));
            row.put("p95Millis", percentile(view.samples, 0.95));
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("2xx", s2.sum());
            status.put("3xx", s3.sum());
            status.put("4xx", s4.sum());
            status.put("5xx", s5.sum());
            row.put("status", status);
            // 上游实例分布：负载均衡是否均匀
            Map<String, Long> instances = new LinkedHashMap<>();
            for (Map.Entry<String, LongAdder> entry : instanceCounts.entrySet()) {
                instances.put(entry.getKey(), entry.getValue().sum());
            }
            row.put("instances", instances);
            return row;
        }

        /** live 用：窗口 + 上一秒；不算 p95，避免每秒对每条 Top 路由排序样本。 */
        Map<String, Object> liveSnapshot(String routeId, long nowSecond, int windowSeconds) {
            TimeRing.WindowView view = ring.view(nowSecond, windowSeconds);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("routeId", routeId);
            row.put("lastSecondRequests", ring.countAt(nowSecond - 1));
            row.put("windowRequests", view.requestCount);
            row.put("errorRate", view.requestCount == 0
                    ? 0 : round2(view.errorCount / (double) view.requestCount));
            row.put("avgMillis", view.requestCount == 0
                    ? 0 : round2(view.sumMillis / (double) view.requestCount));
            Map<String, Long> instances = new LinkedHashMap<>();
            for (Map.Entry<String, LongAdder> entry : instanceCounts.entrySet()) {
                instances.put(entry.getKey(), entry.getValue().sum());
            }
            row.put("instances", instances);
            return row;
        }
    }

    /** 上游维度统计：按 host:port 记录响应时间、连接失败、超时。 */
    static final class UpstreamMetrics {
        final LongAdder requests = new LongAdder();
        final LongAdder sumMillis = new LongAdder();
        final LongAdder connectFail = new LongAdder();
        final LongAdder timeout = new LongAdder();
        final TimeRing ring = new TimeRing(RING_SECONDS);

        void record(long epochSecond, long costMillis, boolean connectFailFlag, boolean timeoutFlag) {
            requests.increment();
            sumMillis.add(Math.max(0, costMillis));
            if (connectFailFlag) {
                connectFail.increment();
            }
            if (timeoutFlag) {
                timeout.increment();
            }
            int status = connectFailFlag || timeoutFlag ? 502 : 200;
            ring.record(epochSecond, Math.max(0, costMillis), connectFailFlag || timeoutFlag, status);
        }

        long windowRequestCount(long nowSecond, int windowSeconds) {
            return ring.view(nowSecond, windowSeconds).requestCount;
        }

        Map<String, Object> snapshot(String hostPort, long nowSecond, int windowSeconds) {
            TimeRing.WindowView view = ring.view(nowSecond, windowSeconds);
            Map<String, Object> row = new LinkedHashMap<>();
            long count = requests.sum();
            row.put("hostPort", hostPort);
            row.put("requests", count);
            row.put("lastSecondRequests", ring.countAt(nowSecond - 1));
            row.put("windowRequests", view.requestCount);
            row.put("avgMillis", view.requestCount == 0
                    ? 0 : round2(view.sumMillis / (double) view.requestCount));
            row.put("connectFail", connectFail.sum());
            row.put("timeout", timeout.sum());
            return row;
        }
    }
}
