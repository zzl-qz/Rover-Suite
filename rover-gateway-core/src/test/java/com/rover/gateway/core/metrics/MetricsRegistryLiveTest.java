package com.rover.gateway.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.common.json.JsonCodec;
import com.rover.common.constants.ManageApiPaths;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetricsRegistryLiveTest {

    @Test
    void liveJson_usesWindowStatusAndKeepsPayloadSmall() {
        MetricsRegistry registry = new MetricsRegistry(new MetricsSettings());
        registry.record("demo", 200, 12, "127.0.0.1:8081", 8, false, false);
        registry.record("demo", 500, 40, "127.0.0.1:8081", 30, false, true);

        @SuppressWarnings("unchecked")
        Map<String, Object> live = JsonCodec.fromJson(registry.liveJson(ManageApiPaths.LIVE_RANGE_1M), Map.class);
        assertEquals(ManageApiPaths.LIVE_RANGE_1M, ((Number) live.get("rangeSeconds")).intValue());
        assertTrue(live.containsKey("traffic"));
        assertTrue(live.containsKey("jvm"));
        assertTrue(live.containsKey("qpsSeries"));

        @SuppressWarnings("unchecked")
        Map<String, Object> traffic = (Map<String, Object>) live.get("traffic");
        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) traffic.get("status");
        assertEquals(1L, ((Number) status.get("2xx")).longValue());
        assertEquals(1L, ((Number) status.get("5xx")).longValue());
        assertEquals(2L, ((Number) traffic.get("windowRequests")).longValue());
    }

    @Test
    void clampRange_onlyAllowsOneOrFiveMinutes() {
        assertEquals(60, MetricsRegistry.clampRange(1));
        assertEquals(60, MetricsRegistry.clampRange(60));
        assertEquals(300, MetricsRegistry.clampRange(120));
        assertEquals(300, MetricsRegistry.clampRange(300));
    }
}
