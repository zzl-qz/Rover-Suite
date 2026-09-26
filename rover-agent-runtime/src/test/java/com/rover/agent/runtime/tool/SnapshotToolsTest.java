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
                    "/api/traces?path=%2Fapi%2Fhello", 1),
            Evidence.of("task-1", EvidenceType.CONFIG, "Gateway / Nameserver 生效配置", "生效配置", "配置快照",
                    "/api/configs", 1),
            Evidence.of("task-1", EvidenceType.EVENT, "Nameserver 事件流", "注册中心事件", "事件快照",
                    "/api/events", 1),
            Evidence.of("task-1", EvidenceType.METRIC, "Gateway 实时指标", "上游实例窗口观测",
                    "上游 order-1（窗口请求 120 次，5xx 0 次）", "/api/metrics/routes?routeId=r3", 1),
            Evidence.of("task-1", EvidenceType.METRIC, "Gateway 实时指标", "上游实例窗口观测",
                    "上游 order-2（窗口请求 118 次，5xx 42 次）", "/api/metrics/routes?routeId=r3", 1));

    @Test
    void exposesOnlyCollectedSnapshotsAndIsRepeatable() {
        SnapshotTools tools = new SnapshotTools(EVIDENCE);

        for (int i = 0; i < 2; i++) {
            assertEquals("路由快照", tools.routeSnapshot());
            assertEquals("实例快照", tools.instanceSnapshot());
            assertEquals("追踪快照", tools.traceSnapshot());
            assertEquals("配置快照", tools.configSnapshot());
            assertEquals("事件快照", tools.eventSnapshot());
            assertEquals("实时指标不可用", tools.metricsSnapshot());
        }
    }

    /** 路由 × 上游实例一次产出多条同前缀证据：必须全部返回，只给第一条会让模型以为只有一台实例。 */
    @Test
    void upstreamSnapshotReturnsEveryInstanceRow() {
        SnapshotTools tools = new SnapshotTools(EVIDENCE);

        String text = tools.upstreamSnapshot();

        assertTrue(text.contains("order-1"), "实际为 " + text);
        assertTrue(text.contains("order-2"), "实际为 " + text);
        assertEquals(List.of("上游实例窗口观测"), tools.calledTools());
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
        assertEquals("按上游实例的指标不可用", tools.upstreamSnapshot());
        assertEquals("配置快照不可用", tools.configSnapshot());
        assertEquals("注册事件不可用", tools.eventSnapshot());
    }
}