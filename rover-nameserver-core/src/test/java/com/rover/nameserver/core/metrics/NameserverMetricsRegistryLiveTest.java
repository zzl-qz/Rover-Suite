package com.rover.nameserver.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.common.constants.ManageApiPaths;
import com.rover.common.json.JsonCodec;
import com.rover.nameserver.core.registry.InMemoryServiceRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NameserverMetricsRegistryLiveTest {

    @Test
    void liveJson_exposesInstantOpsAndJvm() {
        NameserverMetricsRegistry metrics = new NameserverMetricsRegistry();
        metrics.heartbeat("demo", "i1");
        metrics.query("demo");
        metrics.push("demo", 1);

        @SuppressWarnings("unchecked")
        Map<String, Object> live = JsonCodec.fromJson(
                metrics.liveJson(new InMemoryServiceRegistry(), ManageApiPaths.LIVE_RANGE_1M), Map.class);
        assertTrue(live.containsKey("jvm"));
        assertTrue(live.containsKey("instant"));
        assertTrue(live.containsKey("opsSeries"));
        @SuppressWarnings("unchecked")
        Map<String, Object> registry = (Map<String, Object>) live.get("registry");
        assertEquals(0, ((Number) registry.get("instanceCount")).intValue());
    }
}
