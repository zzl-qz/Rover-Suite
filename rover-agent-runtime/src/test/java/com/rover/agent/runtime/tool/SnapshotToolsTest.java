package com.rover.agent.runtime.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.EvidenceType;
import java.util.List;
import java.util.Map;
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
    void prefetchedCoreSnapshotsAreAlwaysAvailableAndNotCountedAsModelCalls() {
        SnapshotTools tools = new SnapshotTools(EVIDENCE);

        Map<String, String> core = tools.coreSnapshots();

        assertEquals(List.of("路由快照", "实例快照"), List.copyOf(core.keySet()));
        assertEquals("路由快照", core.get("路由快照"));
        assertEquals("实例快照", core.get("实例快照"));
        // 预读发生在运行时：不能混进「模型实际调用过的工具」，否则步骤说明会失真。
        assertTrue(tools.calledTools().isEmpty());
        // 核心快照缺失时给占位文本，而不是让整段解读失败。
        Map<String, String> empty = new SnapshotTools(List.of()).coreSnapshots();
        assertEquals("路由数据不可用", empty.get("路由快照"));
        assertEquals("实例数据不可用", empty.get("实例快照"));
    }

    @Test
    void duplicatedEvidenceAddressesDoNotBreakTheSnapshotIndex() {
        List<Evidence> duplicated = List.of(
                Evidence.of("task-1", EvidenceType.ROUTE, "Gateway 路由表", "路由匹配", "路由快照", "/api/routes", 1),
                Evidence.of("task-1", EvidenceType.ROUTE, "Gateway 路由表", "重复地址快照", "另一条同址快照",
                        "/api/routes", 2));

        SnapshotTools tools = new SnapshotTools(duplicated);

        assertEquals("路由快照", tools.routeSnapshot());
        assertEquals("路由快照", tools.coreSnapshots().get("路由快照"));
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