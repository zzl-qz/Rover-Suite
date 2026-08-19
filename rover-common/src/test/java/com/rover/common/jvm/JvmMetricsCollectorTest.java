package com.rover.common.jvm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class JvmMetricsCollectorTest {

    @Test
    void collect_containsLiveGauges() {
        Map<String, Object> jvm = JvmMetricsCollector.collect();
        assertTrue((Double) jvm.get("heapUsedMb") >= 0);
        assertTrue((Double) jvm.get("heapCommittedMb") >= (Double) jvm.get("heapUsedMb") - 0.1);
        assertTrue((Integer) jvm.get("threadCount") > 0);
        assertTrue((Long) jvm.get("gcCount") >= 0);
        assertTrue((Double) jvm.get("processCpuPercent") >= 0);
        assertFalse(jvm.containsKey("error"));
    }
}
