package com.rover.agent.runtime.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.model.Evidence;
import java.util.List;
import org.junit.jupiter.api.Test;

class SnapshotToolsTest {

    private static final List<Evidence> EVIDENCE = List.of(
            new Evidence("/api/routes", 1, "路由快照"),
            new Evidence("/api/instances", 1, "实例快照"),
            new Evidence("/api/traces?path=%2Fapi%2Fhello", 1, "追踪快照"));

    @Test
    void exposesOnlyCollectedSnapshotsAndIsRepeatable() {
        SnapshotTools tools = new SnapshotTools(EVIDENCE);

        for (int i = 0; i < 2; i++) {
            assertEquals("路由快照", tools.routeSnapshot());
            assertEquals("实例快照", tools.instanceSnapshot());
            assertEquals("追踪快照", tools.traceSnapshot());
            assertEquals("实时指标不可用", tools.metricsSnapshot());
        }
    }

    @Test
    void tracksWhetherTheModelActuallyReadTheRequiredEvidence() {
        SnapshotTools tools = new SnapshotTools(EVIDENCE);

        assertFalse(tools.hasReadRoute());
        assertFalse(tools.hasReadInstances());
        tools.routeSnapshot();
        assertTrue(tools.hasReadRoute());
        assertFalse(tools.hasReadInstances());
        tools.instanceSnapshot();
        assertTrue(tools.hasReadInstances());
    }

    @Test
    void missingEvidenceIsReportedInsteadOfThrowing() {
        SnapshotTools tools = new SnapshotTools(List.of());

        assertEquals("路由数据不可用", tools.routeSnapshot());
        assertEquals("实例数据不可用", tools.instanceSnapshot());
        assertEquals("实时指标不可用", tools.metricsSnapshot());
        assertEquals("追踪数据不可用", tools.traceSnapshot());
    }
}