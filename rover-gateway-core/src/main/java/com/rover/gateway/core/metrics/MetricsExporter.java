package com.rover.gateway.core.metrics;

import com.rover.common.constants.RoverComponent;
import com.rover.common.jvm.JvmMetricsCollector;
import com.rover.common.json.JsonCodec;
import com.rover.gateway.core.metrics.MetricsRegistry.RouteMetrics;
import com.rover.gateway.core.metrics.MetricsRegistry.TimeRing;
import com.rover.gateway.core.metrics.MetricsRegistry.UpstreamMetrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * 指标导出：snapshot / live / selfcheck / Prometheus。
 * 热路径 record 仍在 MetricsRegistry；这里只读拼装，方便单独改导出格式。
 */
final class MetricsExporter {

    private static final long LIVE_TOP_CACHE_MILLIS = 5000L;

    private final MetricsRegistry r;

    MetricsExporter(MetricsRegistry registry) {
        this.r = registry;
    }

    /** 组装 /_manage/metrics 的 JSON 内容。 */
    String snapshotJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("component", RoverComponent.GATEWAY.id());
        root.put("enabled", r.settings.isEnabled());
        root.put("windowSeconds", effectiveWindowSeconds());
        root.put("uptimeSeconds", (System.nanoTime() - r.startNanos) / 1_000_000_000L);

        // JVM 运行时指标（两组件通用，零依赖）
        root.put("jvm", JvmMetricsCollector.collect());
        root.put("discovery", r.discoveryStatusSupplier.get());

        // 资源指标：活跃连接 / 在途请求 / 在途上游请求（近似连接池使用）
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("activeConnections", r.activeConnections.get());
        resources.put("inflightRequests", r.inflightRequests.get());
        resources.put("upstreamInFlight", r.upstreamInFlightSupplier.getAsInt());
        resources.put("rejects", rejectCounts());
        resources.put("retries", retryCounts());
        root.put("resources", resources);

        long nowSecond = System.currentTimeMillis() / 1000;
        int window = effectiveWindowSeconds();

        Map<String, Object> total = new LinkedHashMap<>();
        long totalCount = r.totalRequests.sum();
        TimeRing.WindowView view = r.globalRing.view(nowSecond, window);
        total.put("requests", totalCount);
        total.put("windowRequests", view.requestCount);
        // 过期量为派生值，保证 windowRequests + expiredRequests == requests 恒成立
        total.put("expiredRequests", Math.max(0, totalCount - view.requestCount));
        total.put("qps", round2(view.requestCount / (double) view.actualSeconds));
        total.put("instantQps", r.globalRing.countAt(nowSecond - 1));
        total.put("qps5s", qpsOver(nowSecond, 5));
        total.put("qps60s", qpsOver(nowSecond, 60));
        total.put("avgMillis", view.requestCount == 0 ? 0 : round2(view.sumMillis / (double) view.requestCount));
        TimeRing.WindowView lastSecond = r.globalRing.view(nowSecond - 1, 1);
        total.put("instantAvgMillis", lastSecond.requestCount == 0
                ? 0 : round2(lastSecond.sumMillis / (double) lastSecond.requestCount));
        total.put("p95Millis", MetricsRegistry.percentile(view.samples, 0.95));
        total.put("p99Millis", MetricsRegistry.percentile(view.samples, 0.99));
        total.put("maxMillis", view.maxMillis);
        total.put("errorRequests", view.errorCount);

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("2xx", r.status2xx.sum());
        status.put("3xx", r.status3xx.sum());
        status.put("4xx", r.status4xx.sum());
        status.put("5xx", r.status5xx.sum());
        total.put("status", status);
        total.put("windowStatus", statusMap(view));

        Map<String, Object> errors = new LinkedHashMap<>();
        errors.put("routeUnmatched", r.errRouteUnmatched.sum());
        errors.put("proxyTimeout", r.errProxyTimeout.sum());
        errors.put("upstreamConnectFail", r.errUpstreamConnect.sum());
        total.put("gatewayErrors", errors);
        root.put("total", total);

        root.put("qpsSeries", qpsSeries(nowSecond, window));

        List<Map<String, Object>> routeRows = new ArrayList<>();
        List<Map.Entry<String, RouteMetrics>> entries = new ArrayList<>(r.routes.entrySet());
        entries.sort(Comparator.comparingLong(e -> -e.getValue().windowRequestCount(nowSecond, window)));
        for (Map.Entry<String, RouteMetrics> entry : entries) {
            routeRows.add(entry.getValue().snapshot(entry.getKey(), nowSecond, window));
        }
        root.put("routes", routeRows);

        // 上游维度：按 host:port 统计响应时间、连接失败、超时
        List<Map<String, Object>> upstreamRows = new ArrayList<>();
        List<Map.Entry<String, UpstreamMetrics>> upstreamEntries = new ArrayList<>(r.upstreams.entrySet());
        upstreamEntries.sort(Comparator.comparingLong(e -> -e.getValue().requests.sum()));
        for (Map.Entry<String, UpstreamMetrics> entry : upstreamEntries) {
            upstreamRows.add(entry.getValue().snapshot(entry.getKey(), nowSecond, window));
        }
        root.put("upstreams", upstreamRows);
        return JsonCodec.toJson(root);
    }

    /**
     * 轻量实时快照：给 Admin 1 秒轮询用。
     * 相对全量 metrics：不算 p99、不带上游 Top、路由 Top 5 秒缓存且不算路由 p95。
     */
    String liveJson(int rangeSeconds) {
        long nowSecond = System.currentTimeMillis() / 1000;
        int range = MetricsRegistry.clampRange(rangeSeconds);
        TimeRing.WindowView window = r.globalRing.view(nowSecond, range);
        TimeRing.WindowView lastSecond = r.globalRing.view(nowSecond - 1, 1);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("component", RoverComponent.GATEWAY.id());
        root.put("serverTimeMillis", System.currentTimeMillis());
        root.put("rangeSeconds", range);
        root.put("jvm", JvmMetricsCollector.collect());

        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("activeConnections", r.activeConnections.get());
        resources.put("inflightRequests", r.inflightRequests.get());
        resources.put("upstreamInFlight", r.upstreamInFlightSupplier.getAsInt());
        resources.put("rejects", rejectCounts());
        resources.put("retries", retryCounts());
        root.put("resources", resources);

        Map<String, Object> traffic = new LinkedHashMap<>();
        long instantQps = r.globalRing.countAt(nowSecond - 1);
        long currentSecond = r.globalRing.countAt(nowSecond);
        traffic.put("instantQps", instantQps);
        traffic.put("currentSecondRequests", currentSecond);
        traffic.put("qps5s", qpsOver(nowSecond, 5));
        traffic.put("qps60s", qpsOver(nowSecond, 60));
        traffic.put("qps300s", qpsOver(nowSecond, MetricsRegistry.RING_SECONDS));
        traffic.put("instantAvgMillis", lastSecond.requestCount == 0
                ? 0 : round2(lastSecond.sumMillis / (double) lastSecond.requestCount));
        traffic.put("windowRequests", window.requestCount);
        traffic.put("avgMillis", window.requestCount == 0
                ? 0 : round2(window.sumMillis / (double) window.requestCount));
        // live 只保留 p95；p99 留给全量 metrics / Prometheus，避免每秒双次排序样本
        traffic.put("p95Millis", MetricsRegistry.percentile(window.samples, 0.95));
        traffic.put("maxMillis", window.maxMillis);
        traffic.put("errorRequests", window.errorCount);
        traffic.put("status", statusMap(window));
        traffic.put("idle", instantQps == 0 && currentSecond == 0);
        root.put("traffic", traffic);

        Map<String, Object> errors = new LinkedHashMap<>();
        errors.put("routeUnmatched", r.errRouteUnmatched.sum());
        errors.put("proxyTimeout", r.errProxyTimeout.sum());
        errors.put("upstreamConnectFail", r.errUpstreamConnect.sum());
        root.put("gatewayErrors", errors);

        root.put("qpsSeries", qpsSeries(nowSecond, range));
        root.put("routes", topRoutesForLive(nowSecond, range, 8));
        return JsonCodec.toJson(root);
    }

    /** 近 N 秒的平均 QPS，分母固定为窗口长度，空闲就是 0。 */
    private double qpsOver(long nowSecond, int seconds) {
        TimeRing.WindowView view = r.globalRing.view(nowSecond, seconds);
        return round2(view.requestCount / (double) Math.max(1, seconds));
    }

    private static Map<String, Object> statusMap(TimeRing.WindowView view) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("2xx", view.status2xx);
        status.put("3xx", view.status3xx);
        status.put("4xx", view.status4xx);
        status.put("5xx", view.status5xx);
        return status;
    }

    private List<Map<String, Object>> topRoutes(long nowSecond, int window, int limit) {
        List<Map.Entry<String, RouteMetrics>> entries = new ArrayList<>(r.routes.entrySet());
        entries.sort(Comparator.comparingLong(e -> -e.getValue().windowRequestCount(nowSecond, window)));
        List<Map<String, Object>> rows = new ArrayList<>();
        int size = Math.min(limit, entries.size());
        for (int i = 0; i < size; i++) {
            Map.Entry<String, RouteMetrics> entry = entries.get(i);
            rows.add(entry.getValue().liveSnapshot(entry.getKey(), nowSecond, window));
        }
        return rows;
    }

    /** live 专用：Top 列表最多 5 秒算一次，减轻每秒排序。 */
    private List<Map<String, Object>> topRoutesForLive(long nowSecond, int window, int limit) {
        long nowMillis = System.currentTimeMillis();
        long cachedAt = r.liveTopRoutesCachedAtMillis.get();
        if (nowMillis - cachedAt < LIVE_TOP_CACHE_MILLIS && cachedAt > 0) {
            return r.liveTopRoutesCache;
        }
        List<Map<String, Object>> rows = topRoutes(nowSecond, window, limit);
        r.liveTopRoutesCache = rows;
        r.liveTopRoutesCachedAtMillis.set(nowMillis);
        return rows;
    }

    /** QPS 曲线数据：窗口内每秒请求数（缺失秒补 0，前端可直接画线）。 */
    private List<Map<String, Object>> qpsSeries(long nowSecond, int window) {
        List<Map<String, Object>> series = new ArrayList<>();
        for (long second = nowSecond - window + 1; second <= nowSecond; second++) {
            long count = r.globalRing.countAt(second);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("second", second);
            point.put("count", count);
            series.add(point);
        }
        return series;
    }

    /** 自洽性自检：校验各加和关系，返回 JSON。 */
    String selfcheckJson() {
        long nowSecond = System.currentTimeMillis() / 1000;
        int window = effectiveWindowSeconds();

        List<Map<String, Object>> checks = new ArrayList<>();
        boolean allOk = true;

        long total = r.totalRequests.sum();
        long s2 = r.status2xx.sum();
        long s3 = r.status3xx.sum();
        long s4 = r.status4xx.sum();
        long s5 = r.status5xx.sum();
        boolean statusOk = s2 + s3 + s4 + s5 == total;
        allOk &= statusOk;
        checks.add(check("status_sum_equals_total", statusOk,
                "2xx+3xx+4xx+5xx=" + (s2 + s3 + s4 + s5) + ", total=" + total));

        TimeRing.WindowView view = r.globalRing.view(nowSecond, window);
        long expired = Math.max(0, total - view.requestCount);
        boolean windowOk = view.requestCount + expired == total && view.requestCount <= total;
        allOk &= windowOk;
        checks.add(check("window_plus_expired_equals_total", windowOk,
                "window=" + view.requestCount + ", expired=" + expired + ", total=" + total));

        long routeWindowSum = 0;
        long routeTotalSum = 0;
        boolean routeStatusOk = true;
        for (RouteMetrics route : r.routes.values()) {
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

        // 上游维度与路由实例分布自洽：每次真实转发同时累加 r.upstreams 与 route.instances
        long upstreamSum = 0;
        long instanceSum = 0;
        for (UpstreamMetrics upstream : r.upstreams.values()) {
            upstreamSum += upstream.requests.sum();
        }
        for (RouteMetrics route : r.routes.values()) {
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
    String prometheusText() {
        long nowSecond = System.currentTimeMillis() / 1000;
        int window = effectiveWindowSeconds();
        TimeRing.WindowView view = r.globalRing.view(nowSecond, window);

        StringBuilder sb = new StringBuilder(2048);

        sb.append("# HELP rover_gateway_uptime_seconds Process uptime in seconds\n");
        sb.append("# TYPE rover_gateway_uptime_seconds gauge\n");
        sb.append("rover_gateway_uptime_seconds ")
                .append((System.nanoTime() - r.startNanos) / 1_000_000_000.0).append('\n');

        long total = r.totalRequests.sum();
        sb.append("# HELP rover_gateway_requests_total Total requests since startup\n");
        sb.append("# TYPE rover_gateway_requests_total counter\n");
        sb.append("rover_gateway_requests_total{status=\"2xx\"} ").append(r.status2xx.sum()).append('\n');
        sb.append("rover_gateway_requests_total{status=\"3xx\"} ").append(r.status3xx.sum()).append('\n');
        sb.append("rover_gateway_requests_total{status=\"4xx\"} ").append(r.status4xx.sum()).append('\n');
        sb.append("rover_gateway_requests_total{status=\"5xx\"} ").append(r.status5xx.sum()).append('\n');
        sb.append("rover_gateway_requests_total{status=\"all\"} ").append(total).append('\n');

        sb.append("# HELP rover_gateway_errors_total Gateway-side errors by type\n");
        sb.append("# TYPE rover_gateway_errors_total counter\n");
        sb.append("rover_gateway_errors_total{type=\"route_unmatched\"} ").append(r.errRouteUnmatched.sum()).append('\n');
        sb.append("rover_gateway_errors_total{type=\"proxy_timeout\"} ").append(r.errProxyTimeout.sum()).append('\n');
        sb.append("rover_gateway_errors_total{type=\"upstream_connect_fail\"} ").append(r.errUpstreamConnect.sum()).append('\n');

        sb.append("# HELP rover_gateway_active_connections Current active connections\n");
        sb.append("# TYPE rover_gateway_active_connections gauge\n");
        sb.append("rover_gateway_active_connections ").append(r.activeConnections.get()).append('\n');
        sb.append("# HELP rover_gateway_inflight_requests Requests currently being processed\n");
        sb.append("# TYPE rover_gateway_inflight_requests gauge\n");
        sb.append("rover_gateway_inflight_requests ").append(r.inflightRequests.get()).append('\n');
        Map<String, Object> discovery = r.discoveryStatusSupplier.get();
        sb.append("# HELP rover_gateway_discovery_connected Whether service discovery is connected\n");
        sb.append("# TYPE rover_gateway_discovery_connected gauge\n");
        sb.append("rover_gateway_discovery_connected ")
                .append(Boolean.TRUE.equals(discovery.get("connected")) ? 1 : 0).append('\n');
        sb.append("# HELP rover_gateway_discovery_subscribe_failures_total Service discovery subscription failures\n");
        sb.append("# TYPE rover_gateway_discovery_subscribe_failures_total counter\n");
        sb.append("rover_gateway_discovery_subscribe_failures_total ")
                .append(discovery.getOrDefault("subscribeFailures", 0)).append('\n');
        sb.append("# HELP rover_gateway_discovery_last_snapshot_updated_at_millis Last discovery snapshot update time\n");
        sb.append("# TYPE rover_gateway_discovery_last_snapshot_updated_at_millis gauge\n");
        sb.append("rover_gateway_discovery_last_snapshot_updated_at_millis ")
                .append(discovery.getOrDefault("lastSnapshotUpdatedAtMillis", 0)).append('\n');
        sb.append("# HELP rover_gateway_rejects_total Gateway 503 rejects by reason\n");
        sb.append("# TYPE rover_gateway_rejects_total counter\n");
        sb.append("rover_gateway_rejects_total{reason=\"inflight_limit\"} ")
                .append(r.rejectInflightLimit.sum()).append('\n');
        sb.append("rover_gateway_rejects_total{reason=\"no_upstream\"} ")
                .append(r.rejectNoUpstream.sum()).append('\n');
        sb.append("rover_gateway_rejects_total{reason=\"circuit_open\"} ")
                .append(r.rejectCircuitOpen.sum()).append('\n');
        sb.append("# HELP rover_gateway_retries_total Gateway retries by reason\n");
        sb.append("# TYPE rover_gateway_retries_total counter\n");
        sb.append("rover_gateway_retries_total{reason=\"connect\"} ")
                .append(r.retryConnect.sum()).append('\n');

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
        sb.append("rover_gateway_request_duration_ms{stat=\"p95\"} ").append(MetricsRegistry.percentile(view.samples, 0.95)).append('\n');
        sb.append("rover_gateway_request_duration_ms{stat=\"p99\"} ").append(MetricsRegistry.percentile(view.samples, 0.99)).append('\n');
        sb.append("rover_gateway_request_duration_ms{stat=\"max\"} ").append(view.maxMillis).append('\n');

        // 路由维度
        List<Map.Entry<String, RouteMetrics>> entries = new ArrayList<>(r.routes.entrySet());
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
                    .append(MetricsRegistry.percentile(routeView.samples, 0.95)).append('\n');
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

    private Map<String, Object> rejectCounts() {
        Map<String, Object> rejects = new LinkedHashMap<>();
        rejects.put("inflightLimit", r.rejectInflightLimit.sum());
        rejects.put("noUpstream", r.rejectNoUpstream.sum());
        rejects.put("circuitOpen", r.rejectCircuitOpen.sum());
        return rejects;
    }

    private Map<String, Object> retryCounts() {
        Map<String, Object> retries = new LinkedHashMap<>();
        retries.put("connect", r.retryConnect.sum());
        return retries;
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
        int configured = r.settings.getWindowSeconds();
        if (configured <= 0) {
            return MetricsRegistry.RING_SECONDS;
        }
        return Math.min(configured, MetricsRegistry.RING_SECONDS);
    }

    private static double round2(double value) {
        return MetricsRegistry.round2(value);
    }
}
