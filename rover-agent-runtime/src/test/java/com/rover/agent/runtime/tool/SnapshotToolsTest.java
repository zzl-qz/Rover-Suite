package com.rover.agent.runtime.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.EvidenceType;
import java.util.List;
import org.junit.jupiter.api.Test;

class SnapshotToolsTest {

    private static final List<Evidence> EVIDENCE = List.of(
            Evidence.of("task-1", EvidenceType.ROUTE, "Gateway 路由表", "路由匹配", "路由快照", "/api/routes", 1),
            Evidence.of("task-1", EvidenceType.INSTANCE, "Nameserver 实例注册表", "实例健康", "实例快照",
                    "/api/instances", 1),
            Evidence.of("task-1", EvidenceType.TRACE, "Gateway 抽样追踪", "路径追踪记录", "追踪快照",
                    "/api/traces?path=%2Fapi%2Fhello", 1));

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
    void recordsOnlyTheToolsTheModelActuallyCalledInFirstCallOrder() {
        SnapshotTools tools = new SnapshotTools(EVIDENCE);

        assertTrue(tools.calledTools().isEmpty());
        tools.instanceSnapshot();
        tools.routeSnapshot();
        tools.instanceSnapshot();
        assertEquals(List.of("实例快照", "路由快照"), tools.calledTools());
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