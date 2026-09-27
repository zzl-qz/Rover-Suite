package com.rover.gateway.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.common.constants.ManageApiPaths;
import com.rover.common.json.JsonCodec;
import com.rover.gateway.core.route.RouteTarget;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** /_manage/metrics/routes JSON 契约测试（字段名与嵌套必须与 Admin 适配器一致）。 */
class MetricsExporterTest {

    @Test
    void routeMetricsJson_hasContractFieldsAndRows() {
        MetricsRegistry registry = new MetricsRegistry(new MetricsSettings());
        registry.record("order-route", 500, 6, "10.0.0.2:8080", 5, false, false);

        @SuppressWarnings("unchecked")
        Map<String, Object> root = JsonCodec.fromJson(
                registry.routeMetricsJson("order-route", ManageApiPaths.LIVE_RANGE_1M), Map.class);

        assertEquals("gateway", root.get("component"));
        assertEquals(ManageApiPaths.LIVE_RANGE_1M, ((Number) root.get("windowSeconds")).intValue());
        assertEquals("order-route", root.get("routeId"));
        assertTrue(root.containsKey("serverTimeMillis"));
        assertTrue(root.containsKey("observedAtMillis"));
        // 指标启用时不写 enabled 字段，Admin 侧缺省视为启用
        assertFalse(root.containsKey("enabled"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) root.get("rows");
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals("order-route", row.get("routeId"));
        assertEquals("10.0.0.2:8080", row.get("hostPort"));
        assertEquals(1L, ((Number) row.get("windowRequests")).longValue());
        assertTrue(row.containsKey("errorRate"));
        assertTrue(row.containsKey("avgMillis"));
        assertTrue(row.containsKey("p95Millis"));
        assertTrue(row.containsKey("p95Samples"));
        assertEquals(0L, ((Number) row.get("connectFail")).longValue());
        assertEquals(0L, ((Number) row.get("timeout")).longValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) row.get("status");
        assertEquals(1L, ((Number) status.get("5xx")).longValue());
    }

    @Test
    void routeMetricsJson_exposesVersionAttributionAndDeclaredTargets() {
        MetricsRegistry registry = new MetricsRegistry(new MetricsSettings());
        registry.setRouteTargetsSupplier(id -> List.of(
                new RouteTarget("order-service", "v1", 95),
                new RouteTarget("order-service", "v2", 5)));
        registry.record("order-route", 500, 6, "10.0.0.2:8080", 5, false, false, "v2");

        @SuppressWarnings("unchecked")
        Map<String, Object> root = JsonCodec.fromJson(
                registry.routeMetricsJson("order-route", ManageApiPaths.LIVE_RANGE_1M), Map.class);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> targets = (List<Map<String, Object>>) root.get("targets");
        assertEquals(2, targets.size());
        assertEquals("v1", targets.get(0).get("group"));
        assertEquals(95, ((Number) targets.get(0).get("weight")).intValue());

        // 实例行带版本归属
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) root.get("rows");
        assertEquals(1, rows.size());
        assertEquals("v2", rows.get(0).get("group"));

        // 按版本聚合：声明的 v1 窗口内没有流量，样本不足
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byVersion = (List<Map<String, Object>>) root.get("byVersion");
        assertEquals(2, byVersion.size());
        Map<String, Object> v1 = byVersion.stream()
                .filter(r -> "v1".equals(r.get("group"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, v1.get("declared"));
        assertEquals(0L, ((Number) v1.get("sampleSize")).longValue());
        assertEquals(Boolean.FALSE, v1.get("sufficient"));

        @SuppressWarnings("unchecked")
        Map<String, Object> check = (Map<String, Object>) root.get("versionCheck");
        assertEquals(List.of("v1"), check.get("missingGroups"));
        assertEquals(List.of("v2"), check.get("observedGroups"));
        assertEquals(5, ((Number) check.get("sampleThreshold")).intValue());
    }

    @Test
    void routeMetricsJson_clampsRangeToSupportedBuckets() {
        MetricsRegistry registry = new MetricsRegistry(new MetricsSettings());
        @SuppressWarnings("unchecked")
        Map<String, Object> root = JsonCodec.fromJson(registry.routeMetricsJson("r", 500), Map.class);
        assertEquals(ManageApiPaths.LIVE_RANGE_5M, ((Number) root.get("windowSeconds")).intValue());
    }

    @Test
    void routeMetricsJson_disabledReturnsValidJsonWithEmptyRows() {
        MetricsSettings settings = new MetricsSettings();
        settings.setEnabled(false);
        MetricsRegistry registry = new MetricsRegistry(settings);

        @SuppressWarnings("unchecked")
        Map<String, Object> root = JsonCodec.fromJson(
                registry.routeMetricsJson("order-route", ManageApiPaths.LIVE_RANGE_1M), Map.class);

        assertEquals(Boolean.FALSE, root.get("enabled"));
        assertTrue(((List<?>) root.get("rows")).isEmpty());
        assertEquals("order-route", root.get("routeId"));
    }
}
