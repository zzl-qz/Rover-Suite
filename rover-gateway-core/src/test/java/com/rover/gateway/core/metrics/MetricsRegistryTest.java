package com.rover.gateway.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.common.constants.ManageApiPaths;
import com.rover.common.json.JsonCodec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 「路由 × 上游实例」窗口观测与自洽自检的单元测试。 */
class MetricsRegistryTest {

    private static MetricsRegistry enabledRegistry() {
        return new MetricsRegistry(new MetricsSettings());
    }

    @Test
    void routeUpstreamRows_separatesInstancesByRealStatusCode() {
        MetricsRegistry registry = enabledRegistry();
        // 同一路由下两台实例：一台 200，一台 500，应分别成行
        registry.record("order-route", 200, 5, "10.0.0.1:8080", 4, false, false);
        registry.record("order-route", 500, 6, "10.0.0.2:8080", 5, false, false);

        List<Map<String, Object>> rows = registry.routeUpstreamRows("order-route", ManageApiPaths.LIVE_RANGE_1M);
        assertEquals(2, rows.size());

        Map<String, Object> healthy = rowOf(rows, "10.0.0.1:8080");
        assertEquals("order-route", healthy.get("routeId"));
        assertEquals(1L, number(healthy.get("windowRequests")));
        assertEquals(0L, status5xx(healthy));
        assertEquals(0.0, number(healthy.get("errorRate")));

        Map<String, Object> failing = rowOf(rows, "10.0.0.2:8080");
        assertEquals(1L, number(failing.get("windowRequests")));
        assertEquals(1L, status5xx(failing));
        assertEquals(1.0, number(failing.get("errorRate")));
        assertTrue(number(failing.get("p95Samples")) >= 1L);
    }

    @Test
    void routeUpstreamRows_emptyForBlankOrUnknownRoute() {
        MetricsRegistry registry = enabledRegistry();
        registry.record("known-route", 200, 3, "10.0.0.1:8080", 2, false, false);

        assertTrue(registry.routeUpstreamRows(null, ManageApiPaths.LIVE_RANGE_1M).isEmpty());
        assertTrue(registry.routeUpstreamRows("  ", ManageApiPaths.LIVE_RANGE_1M).isEmpty());
        assertTrue(registry.routeUpstreamRows("unknown-route", ManageApiPaths.LIVE_RANGE_1M).isEmpty());
    }

    @Test
    void routeUpstreamRows_emptyWhenNoTrafficInsideWindow() {
        MetricsRegistry registry = enabledRegistry();
        long nowSecond = System.currentTimeMillis() / 1000;
        // 直接构造一条「窗口外」的历史记录，验证没有窗口内流量时返回空列表
        MetricsRegistry.RouteMetrics route = new MetricsRegistry.RouteMetrics();
        MetricsRegistry.UpstreamMetrics stale = new MetricsRegistry.UpstreamMetrics();
        stale.record(nowSecond - 400, 5, 200, false, false);
        route.upstreamMetrics.put("10.0.0.9:8080", stale);
        registry.routes.put("stale-route", route);

        assertTrue(registry.routeUpstreamRows("stale-route", ManageApiPaths.LIVE_RANGE_1M).isEmpty());
    }

    @Test
    void connectFailAndTimeoutAreWindowScopedNotCumulative() {
        long nowSecond = System.currentTimeMillis() / 1000;
        MetricsRegistry.UpstreamMetrics upstream = new MetricsRegistry.UpstreamMetrics();
        // 一条早已滑出窗口，一条在窗口内：累计各 2/1，但窗口只能是 1/0
        upstream.record(nowSecond - 400, 10, 500, true, true);
        upstream.record(nowSecond, 10, 502, true, false);

        Map<String, Object> window = upstream.windowSnapshot(
                "order-route", "10.0.0.1:8080", nowSecond, ManageApiPaths.LIVE_RANGE_1M);
        assertEquals(1L, number(window.get("windowRequests")));
        assertEquals(1L, number(window.get("connectFail")));
        assertEquals(0L, number(window.get("timeout")));

        // 累计口径确实是 2/1，证明上面拿到的是窗口值而非累计值
        assertEquals(2L, upstream.connectFail.sum());
        assertEquals(1L, upstream.timeout.sum());
    }

    @Test
    void selfcheck_routeUpstreamEqualsInstanceAndAllChecksPass() {
        MetricsRegistry registry = enabledRegistry();
        registry.record("order-route", 200, 4, "10.0.0.1:8080", 3, false, false);
        registry.record("order-route", 500, 7, "10.0.0.2:8080", 6, false, false);
        registry.record("order-route", 503, 7, null, 0, false, false);

        @SuppressWarnings("unchecked")
        Map<String, Object> root = JsonCodec.fromJson(registry.selfcheckJson(), Map.class);
        assertTrue((Boolean) root.get("ok"), "自检必须整体通过");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) root.get("checks");
        Map<String, Object> target = checks.stream()
                .filter(c -> "route_upstream_sum_equals_instance_sum".equals(c.get("name")))
                .findFirst()
                .orElseThrow();
        assertEquals(Boolean.TRUE, target.get("ok"));
        assertTrue(String.valueOf(target.get("detail")).contains("upstreamSum=2"));
        assertFalse(checks.stream().anyMatch(c -> Boolean.FALSE.equals(c.get("ok"))));
    }

    private static long status5xx(Map<String, Object> row) {
        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) row.get("status");
        return number(status.get("5xx"));
    }

    private static Map<String, Object> rowOf(List<Map<String, Object>> rows, String hostPort) {
        return rows.stream()
                .filter(r -> hostPort.equals(r.get("hostPort")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少实例行: " + hostPort));
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }
}
