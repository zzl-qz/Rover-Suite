package com.rover.gateway.core.metrics;

import com.rover.common.json.JsonCodec;
import com.rover.common.jvm.JvmMetricsCollector;
import com.rover.common.constants.RoverComponent;
import com.rover.gateway.core.config.GatewayDefaults;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntSupplier;

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
    private final MetricsSettings settings;

    /** 启动纳秒时间（单调时钟），用于 uptime 与耗时统计。 */
    private final long startNanos = System.nanoTime();

    /** 全局累计请求数。 */
    private final LongAdder totalRequests = new LongAdder();

    /** 全局状态码累计（四类互斥且穷尽，加和恒等于 totalRequests）。 */
    private final LongAdder status2xx = new LongAdder();
    private final LongAdder status3xx = new LongAdder();
    private final LongAdder status4xx = new LongAdder();
    private final LongAdder status5xx = new LongAdder();

    /** 网关自身错误累计（按类型）。 */
    private final LongAdder errRouteUnmatched = new LongAdder();
    private final LongAdder errProxyTimeout = new LongAdder();
    private final LongAdder errUpstreamConnect = new LongAdder();

    /** 活跃连接数（Netty 接入连接，只增只减不随窗口过期）。 */
    private final AtomicInteger activeConnections = new AtomicInteger();

    /** 在途请求数（正在处理中未返回的请求）。 */
    private final AtomicInteger inflightRequests = new AtomicInteger();

    /** 全局按秒环形窗口。 */
    private final TimeRing globalRing = new TimeRing(RING_SECONDS);

    /** 路由维度统计，键为 routeId（未匹配归 UNMATCHED_ROUTE）。 */
    private final ConcurrentHashMap<String, RouteMetrics> routes = new ConcurrentHashMap<>();

    /** 上游维度统计，键为 host:port，记录上游响应时间与连接失败/超时。 */
    private final ConcurrentHashMap<String, UpstreamMetrics> upstreams = new ConcurrentHashMap<>();

    /** 在途上游请求数来源，由 HttpProxyClient 提供（近似连接池使用情况）。 */
    private volatile IntSupplier upstreamInFlightSupplier = () -> 0;

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

        globalRing.record(epochSecond, cost, gatewayError);
        RouteMetrics routeMetrics = routes.computeIfAbsent(routeKey, key -> new RouteMetrics());
        routeMetrics.record(statusCode, epochSecond, cost);

        // 上游维度 + 路由实例分布：只有真实转发到上游才统计
        if (upstreamHostPort != null && !upstreamHostPort.isBlank()) {
            upstreams.computeIfAbsent(upstreamHostPort, key -> new UpstreamMetrics())
                    .record(upstreamCostMillis, connectFail, timeout);
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
        activeConnections.incrementAndGet();
    }

    /** 记录一次连接关闭（Netty channelInactive），夹到 0 防止并发下负数。 */
    public void connectionClosed() {
        activeConnections.updateAndGet(v -> Math.max(0, v - 1));
    }

    /** 记录一个请求开始处理（在途 +1）。 */
    public void requestStarted() {
        inflightRequests.incrementAndGet();
    }

    /** 记录一个请求处理结束（在途 -1），夹到 0。 */
    public void requestFinished() {
        inflightRequests.updateAndGet(v -> Math.max(0, v - 1));
    }

    /** 组装 /_manage/metrics 的 JSON 内容。 */
    public String snapshotJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("component", RoverComponent.GATEWAY.id());
        root.put("enabled", settings.isEnabled());
        root.put("windowSeconds", effectiveWindowSeconds());
        root.put("uptimeSeconds", (System.nanoTime() - startNanos) / 1_000_000_000L);

        // JVM 运行时指标（两组件通用，零依赖）
        root.put("jvm", JvmMetricsCollector.collect());

        // 资源指标：活跃连接 / 在途请求 / 在途上游请求（近似连接池使用）
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("activeConnections", activeConnections.get());
        resources.put("inflightRequests", inflightRequests.get());
        resources.put("upstreamInFlight", upstreamInFlightSupplier.getAsInt());
        root.put("resources", resources);

        long nowSecond = System.currentTimeMillis() / 1000;
        int window = effectiveWindowSeconds();

        Map<String, Object> total = new LinkedHashMap<>();
        long totalCount = totalRequests.sum();
        TimeRing.WindowView view = globalRing.view(nowSecond, window);
        total.put("requests", totalCount);
        total.put("windowRequests", view.requestCount);
        // 过期量为派生值，保证 windowRequests + expiredRequests == requests 恒成立
        total.put("expiredRequests", Math.max(0, totalCount - view.requestCount));
        total.put("qps", round2(view.requestCount / (double) view.actualSeconds));
        total.put("avgMillis", view.requestCount == 0 ? 0 : round2(view.sumMillis / (double) view.requestCount));
        total.put("p95Millis", percentile(view.samples, 0.95));
        total.put("p99Millis", percentile(view.samples, 0.99));
        total.put("maxMillis", view.maxMillis);
        total.put("errorRequests", view.errorCount);

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("2xx", status2xx.sum());
        status.put("3xx", status3xx.sum());
        status.put("4xx", status4xx.sum());
        status.put("5xx", status5xx.sum());
        total.put("status", status);

        Map<String, Object> errors = new LinkedHashMap<>();
        errors.put("routeUnmatched", errRouteUnmatched.sum());
        errors.put("proxyTimeout", errProxyTimeout.sum());
        errors.put("upstreamConnectFail", errUpstreamConnect.sum());
        total.put("gatewayErrors", errors);
        root.put("total", total);

        root.put("qpsSeries", qpsSeries(nowSecond, window));

        List<Map<String, Object>> routeRows = new ArrayList<>();
        List<Map.Entry<String, RouteMetrics>> entries = new ArrayList<>(routes.entrySet());
        entries.sort(Comparator.comparingLong(e -> -e.getValue().windowRequestCount(nowSecond, window)));
        for (Map.Entry<String, RouteMetrics> entry : entries) {
            routeRows.add(entry.getValue().snapshot(entry.getKey(), nowSecond, window));
        }
        root.put("routes", routeRows);

        // 上游维度：按 host:port 统计响应时间、连接失败、超时
        List<Map<String, Object>> upstreamRows = new ArrayList<>();
        List<Map.Entry<String, UpstreamMetrics>> upstreamEntries = new ArrayList<>(upstreams.entrySet());
        upstreamEntries.sort(Comparator.comparingLong(e -> -e.getValue().requests.sum()));
        for (Map.Entry<String, UpstreamMetrics> entry : upstreamEntries) {
            upstreamRows.add(entry.getValue().snapshot(entry.getKey()));
        }
        root.put("upstreams", upstreamRows);
        return JsonCodec.toJson(root);
    }

    /** QPS 曲线数据：窗口内每秒请求数（缺失秒补 0，前端可直接画线）。 */
    private List<Map<String, Object>> qpsSeries(long nowSecond, int window) {
        List<Map<String, Object>> series = new ArrayList<>();
        for (long second = nowSecond - window + 1; second <= nowSecond; second++) {
            long count = globalRing.countAt(second);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("second", second);
            point.put("count", count);
            series.add(point);
        }
        return series;
    }

    /** 自洽性自检：校验各加和关系，返回 JSON。 */
    public String selfcheckJson() {
        long nowSecond = System.currentTimeMillis() / 1000;
        int window = effectiveWindowSeconds();

        List<Map<String, Object>> checks = new ArrayList<>();
        boolean allOk = true;

        long total = totalRequests.sum();
        long s2 = status2xx.sum();
        long s3 = status3xx.sum();
        long s4 = status4xx.sum();
        long s5 = status5xx.sum();
        boolean statusOk = s2 + s3 + s4 + s5 == total;
        allOk &= statusOk;
        checks.add(check("status_sum_equals_total", statusOk,
                "2xx+3xx+4xx+5xx=" + (s2 + s3 + s4 + s5) + ", total=" + total));

        TimeRing.WindowView view = globalRing.view(nowSecond, window);
        long expired = Math.max(0, total - view.requestCount);
        boolean windowOk = view.requestCount + expired == total && view.requestCount <= total;
        allOk &= windowOk;
        checks.add(check("window_plus_expired_equals_total", windowOk,
                "window=" + view.requestCount + ", expired=" + expired + ", total=" + total));

        long routeWindowSum = 0;
        long routeTotalSum = 0;
        boolean routeStatusOk = true;
        for (RouteMetrics route : routes.values()) {
            routeWindowSum += route.windowRequestCount(nowSecond, window);
            routeTotalSum += route.total.sum();
            routeStatusOk &= route.statusSumEqualsTotal();
        }
        boolean routeWindowOk = routeWindowSum == view.requestCount;
        boolean routeTotalOk = routeTotalSum == total;
        allOk &= routeWindowOk && routeTotalOk && routeStatusOk;
        checks.add(check("route_window_sum_equals_global_window", routeWindowOk,
                "routeWindowSum=" + routeWindowSum + ", globalWindow=" + view.requestCount));
        checks.add(check("route_total_sum_equals_global_total", routeTotalOk,
                "routeTotalSum=" + routeTotalSum + ", total=" + total));
        checks.add(check("route_status_sum_equals_route_total", routeStatusOk,
                "each route 2xx+3xx+4xx+5xx == route total"));

        // 上游维度与路由实例分布自洽：每次真实转发同时累加 upstreams 与 route.instances
        long upstreamSum = 0;
        long instanceSum = 0;
        for (UpstreamMetrics upstream : upstreams.values()) {
            upstreamSum += upstream.requests.sum();
        }
        for (RouteMetrics route : routes.values()) {
            for (LongAdder adder : route.instanceCounts.values()) {
                instanceSum += adder.sum();
            }
        }
        boolean upstreamOk = upstreamSum == instanceSum;
        allOk &= upstreamOk;
        checks.add(check("upstream_sum_equals_instance_sum", upstreamOk,
                "upstreamSum=" + upstreamSum + ", instanceSum=" + instanceSum));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("ok", allOk);
        root.put("checks", checks);
        return JsonCodec.toJson(root);
    }

    private Map<String, Object> check(String name, boolean ok, String detail) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("ok", ok);
        row.put("detail", detail);
        return row;
    }

    /**
     * Prometheus 文本格式导出（不内置依赖，只是格式转换）。
     * 供有需要的用户对接外部 Grafana/Prometheus，与 snapshotJson 同一数据源派生。
     */
    public String prometheusText() {
        long nowSecond = System.currentTimeMillis() / 1000;
        int window = effectiveWindowSeconds();
        TimeRing.WindowView view = globalRing.view(nowSecond, window);

        StringBuilder sb = new StringBuilder(2048);

        sb.append("# HELP rover_gateway_uptime_seconds Process uptime in seconds\n");
        sb.append("# TYPE rover_gateway_uptime_seconds gauge\n");
        sb.append("rover_gateway_uptime_seconds ")
                .append((System.nanoTime() - startNanos) / 1_000_000_000.0).append('\n');

        long total = totalRequests.sum();
        sb.append("# HELP rover_gateway_requests_total Total requests since startup\n");
        sb.append("# TYPE rover_gateway_requests_total counter\n");
        sb.append("rover_gateway_requests_total{status=\"2xx\"} ").append(status2xx.sum()).append('\n');
        sb.append("rover_gateway_requests_total{status=\"3xx\"} ").append(status3xx.sum()).append('\n');
        sb.append("rover_gateway_requests_total{status=\"4xx\"} ").append(status4xx.sum()).append('\n');
        sb.append("rover_gateway_requests_total{status=\"5xx\"} ").append(status5xx.sum()).append('\n');
        sb.append("rover_gateway_requests_total{status=\"all\"} ").append(total).append('\n');

        sb.append("# HELP rover_gateway_errors_total Gateway-side errors by type\n");
        sb.append("# TYPE rover_gateway_errors_total counter\n");
        sb.append("rover_gateway_errors_total{type=\"route_unmatched\"} ").append(errRouteUnmatched.sum()).append('\n');
        sb.append("rover_gateway_errors_total{type=\"proxy_timeout\"} ").append(errProxyTimeout.sum()).append('\n');
        sb.append("rover_gateway_errors_total{type=\"upstream_connect_fail\"} ").append(errUpstreamConnect.sum()).append('\n');

        sb.append("# HELP rover_gateway_active_connections Current active connections\n");
        sb.append("# TYPE rover_gateway_active_connections gauge\n");
        sb.append("rover_gateway_active_connections ").append(activeConnections.get()).append('\n');
        sb.append("# HELP rover_gateway_inflight_requests Requests currently being processed\n");
        sb.append("# TYPE rover_gateway_inflight_requests gauge\n");
        sb.append("rover_gateway_inflight_requests ").append(inflightRequests.get()).append('\n');

        sb.append("# HELP rover_gateway_window_requests Requests in current window\n");
        sb.append("# TYPE rover_gateway_window_requests gauge\n");
        sb.append("rover_gateway_window_requests ").append(view.requestCount).append('\n');
        sb.append("# HELP rover_gateway_qps Requests per second in window\n");
        sb.append("# TYPE rover_gateway_qps gauge\n");
        sb.append("rover_gateway_qps ").append(round2(view.requestCount / (double) view.actualSeconds)).append('\n');
        sb.append("# HELP rover_gateway_request_duration_ms Request duration stats\n");
        sb.append("# TYPE rover_gateway_request_duration_ms gauge\n");
        sb.append("rover_gateway_request_duration_ms{stat=\"avg\"} ")
                .append(view.requestCount == 0 ? 0 : round2(view.sumMillis / (double) view.requestCount)).append('\n');
        sb.append("rover_gateway_request_duration_ms{stat=\"p95\"} ").append(percentile(view.samples, 0.95)).append('\n');
        sb.append("rover_gateway_request_duration_ms{stat=\"p99\"} ").append(percentile(view.samples, 0.99)).append('\n');
        sb.append("rover_gateway_request_duration_ms{stat=\"max\"} ").append(view.maxMillis).append('\n');

        // 路由维度
        List<Map.Entry<String, RouteMetrics>> entries = new ArrayList<>(routes.entrySet());
        entries.sort(Comparator.comparingLong(e -> -e.getValue().windowRequestCount(nowSecond, window)));
        for (Map.Entry<String, RouteMetrics> entry : entries) {
            String route = prometheusLabel(entry.getKey());
            TimeRing.WindowView routeView = entry.getValue().ring.view(nowSecond, window);
            sb.append("rover_gateway_route_requests_total{route=\"").append(route).append("\"} ")
                    .append(entry.getValue().total.sum()).append('\n');
            sb.append("rover_gateway_route_window_requests{route=\"").append(route).append("\"} ")
                    .append(routeView.requestCount).append('\n');
            sb.append("rover_gateway_route_qps{route=\"").append(route).append("\"} ")
                    .append(round2(routeView.requestCount / (double) routeView.actualSeconds)).append('\n');
            sb.append("rover_gateway_route_error_rate{route=\"").append(route).append("\"} ")
                    .append(routeView.requestCount == 0
                            ? 0 : round2(routeView.errorCount / (double) routeView.requestCount)).append('\n');
            sb.append("rover_gateway_route_duration_ms{route=\"").append(route).append("\",stat=\"avg\"} ")
                    .append(routeView.requestCount == 0 ? 0 : round2(routeView.sumMillis / (double) routeView.requestCount)).append('\n');
            sb.append("rover_gateway_route_duration_ms{route=\"").append(route).append("\",stat=\"p95\"} ")
                    .append(percentile(routeView.samples, 0.95)).append('\n');
        }

        // JVM 运行时（两组件通用，零依赖）
        Map<String, Object> jvm = JvmMetricsCollector.collect();
        sb.append("# HELP rover_jvm_heap_used_mb Heap memory used in MB\n");
        sb.append("# TYPE rover_jvm_heap_used_mb gauge\n");
        sb.append("rover_jvm_heap_used_mb ").append(jvm.getOrDefault("heapUsedMb", 0)).append('\n');
        sb.append("# HELP rover_jvm_heap_max_mb Heap memory max in MB\n");
        sb.append("# TYPE rover_jvm_heap_max_mb gauge\n");
        sb.append("rover_jvm_heap_max_mb ").append(jvm.getOrDefault("heapMaxMb", 0)).append('\n');
        sb.append("# HELP rover_jvm_heap_used_percent Heap usage percent\n");
        sb.append("# TYPE rover_jvm_heap_used_percent gauge\n");
        sb.append("rover_jvm_heap_used_percent ").append(jvm.getOrDefault("heapUsedPercent", 0)).append('\n');
        sb.append("# HELP rover_jvm_nonheap_used_mb Non-heap memory used in MB\n");
        sb.append("# TYPE rover_jvm_nonheap_used_mb gauge\n");
        sb.append("rover_jvm_nonheap_used_mb ").append(jvm.getOrDefault("nonHeapUsedMb", 0)).append('\n');
        sb.append("# HELP rover_jvm_thread_count Current live thread count\n");
        sb.append("# TYPE rover_jvm_thread_count gauge\n");
        sb.append("rover_jvm_thread_count ").append(jvm.getOrDefault("threadCount", 0)).append('\n');
        sb.append("# HELP rover_jvm_gc_total GC total count\n");
        sb.append("# TYPE rover_jvm_gc_total counter\n");
        sb.append("rover_jvm_gc_total ").append(jvm.getOrDefault("gcCount", 0)).append('\n');
        sb.append("# HELP rover_jvm_gc_time_millis_total GC total time in millis\n");
        sb.append("# TYPE rover_jvm_gc_time_millis_total counter\n");
        sb.append("rover_jvm_gc_time_millis_total ").append(jvm.getOrDefault("gcTimeMillis", 0)).append('\n');
        return sb.toString();
    }

    /** Prometheus 标签值转义：反斜杠、双引号、换行。 */
    private static String prometheusLabel(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /** 实际统计窗口（秒），不超过环形数组上限。 */
    private int effectiveWindowSeconds() {
        int configured = settings.getWindowSeconds();
        if (configured <= 0) {
            return RING_SECONDS;
        }
        return Math.min(configured, RING_SECONDS);
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

    private static double round2(double value) {
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
            Slot slot = slots[(int) Math.floorMod(epochSecond, slots.length)];
            if (slot.stamp != epochSecond) {
                synchronized (slot) {
                    if (slot.stamp != epochSecond) {
                        // 先取基线再发布新 stamp，保证看到新 stamp 的线程必看到新基线
                        slot.baseCount = slot.count.sum();
                        slot.baseSum = slot.sumMillis.sum();
                        slot.baseErrors = slot.errors.sum();
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
            long currentMax = slot.maxMillis.get();
            while (costMillis > currentMax) {
                if (slot.maxMillis.compareAndSet(currentMax, costMillis)) {
                    break;
                }
                currentMax = slot.maxMillis.get();
            }
            reservoirAdd(slot, costMillis);
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
                // 秒切换瞬间的极小并发误差可能导致 delta 为负，夹到 0 保证展示自洽
                count = Math.max(0, count);
                sum = Math.max(0, sum);
                errs = Math.max(0, errs);
                view.requestCount += count;
                view.sumMillis += sum;
                view.errorCount += errs;
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
            final LongAdder count = new LongAdder();
            final LongAdder sumMillis = new LongAdder();
            final LongAdder errors = new LongAdder();
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
            ring.record(epochSecond, costMillis, statusCode >= 500);
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
    }

    /** 上游维度统计：按 host:port 记录响应时间、连接失败、超时。 */
    static final class UpstreamMetrics {
        final LongAdder requests = new LongAdder();
        final LongAdder sumMillis = new LongAdder();
        final LongAdder connectFail = new LongAdder();
        final LongAdder timeout = new LongAdder();

        void record(long costMillis, boolean connectFailFlag, boolean timeoutFlag) {
            requests.increment();
            sumMillis.add(Math.max(0, costMillis));
            if (connectFailFlag) {
                connectFail.increment();
            }
            if (timeoutFlag) {
                timeout.increment();
            }
        }

        Map<String, Object> snapshot(String hostPort) {
            Map<String, Object> row = new LinkedHashMap<>();
            long count = requests.sum();
            row.put("hostPort", hostPort);
            row.put("requests", count);
            row.put("avgMillis", count == 0 ? 0 : round2(sumMillis.sum() / (double) count));
            row.put("connectFail", connectFail.sum());
            row.put("timeout", timeout.sum());
            return row;
        }
    }
}
