package com.rover.gateway.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.common.constants.HttpConstants;
import org.junit.jupiter.api.Test;

class MetricsRegistryDisabledTest {

    @Test
    void disabledSkipsInflightAndRecord() {
        MetricsSettings settings = new MetricsSettings();
        settings.setEnabled(false);
        MetricsRegistry registry = new MetricsRegistry(settings);

        registry.requestStarted();
        registry.connectionOpened();
        registry.record("demo", 200, 5);

        assertEquals(0, registry.inflightRequests.get());
        assertEquals(0, registry.activeConnections.get());
        assertEquals(0, registry.totalRequests.sum());
    }

    @Test
    void enabledStillCounts() {
        MetricsRegistry registry = new MetricsRegistry(new MetricsSettings());

        registry.requestStarted();
        registry.record("demo", 200, 5);

        assertEquals(1, registry.inflightRequests.get());
        assertEquals(1, registry.totalRequests.sum());
    }

    @Test
    void rejectCountsEvenWhenDisabled() {
        MetricsSettings settings = new MetricsSettings();
        settings.setEnabled(false);
        MetricsRegistry registry = new MetricsRegistry(settings);

        registry.recordReject(HttpConstants.REJECT_INFLIGHT_LIMIT);
        registry.recordReject(HttpConstants.REJECT_NO_UPSTREAM);
        registry.recordReject(HttpConstants.REJECT_NO_UPSTREAM);

        assertEquals(1, registry.rejectInflightLimit.sum());
        assertEquals(2, registry.rejectNoUpstream.sum());
        assertTrue(registry.snapshotJson().contains("\"inflightLimit\":1"));
        assertTrue(registry.prometheusText().contains("reason=\"inflight_limit\""));
    }
}
