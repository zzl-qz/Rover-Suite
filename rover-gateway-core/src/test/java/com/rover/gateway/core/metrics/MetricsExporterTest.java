package com.rover.gateway.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.common.constants.ManageApiPaths;
import com.rover.common.json.JsonCodec;
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
