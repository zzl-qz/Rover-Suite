package com.rover.admin.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rover.admin.config.AdminProperties;
import com.rover.admin.service.AdminConfigService;
import com.rover.common.constants.RoverComponent;
import com.rover.common.log.JdbcRecordStore;
import com.rover.common.log.LogQuery;
import com.rover.common.log.Record;
import com.rover.common.log.RecordStore;
import com.rover.common.log.RecordType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TelemetryCollectorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void collectWritesMetricsComponentAndInstanceFlipsAndDedupedTraces() throws Exception {
        AdminConfigService service = mock(AdminConfigService.class);
        AdminProperties props = new AdminProperties();
        props.setLogCollectIntervalSeconds(3600); // 测试期间不让后台线程触发

        when(service.loadMetrics()).thenReturn(mapper.readTree("{\"requests\":123}"));
        when(service.loadNameserverMetrics()).thenReturn(mapper.readTree("{\"heartbeat\":45}"));
        // 组件：第一轮 up，第二轮 down → 两个组件各一次翻转
        when(service.loadStatus()).thenReturn(statusMap(true)).thenReturn(statusMap(false));
        // 实例：第一轮基线 healthy，第二轮 unhealthy → 一次 down
        when(service.listInstances())
                .thenReturn(instances(Map.of("i1", true)))
                .thenReturn(instances(Map.of("i1", false)));
        // 慢 + 错误两路返回同一条 trace（既慢又 500），应按 traceId 去重只写一次
        when(service.loadTraces(anyMap())).thenReturn(mapper.readTree(
                "{\"traces\":[{\"traceId\":\"t1\",\"path\":\"/api/x\",\"routeId\":\"r1\","
                        + "\"statusCode\":500,\"startMillis\":1000,\"slow\":true}]}"));

        try (RecordStore store = new JdbcRecordStore("./target/test-logs/collector-" + System.nanoTime())) {
            try (TelemetryCollector collector = new TelemetryCollector(service, store, props)) {
                collector.collect(); // 第一轮：基线（组件 up、实例 healthy），记指标 + 链路
                collector.collect(); // 第二轮：组件 down、实例 down；链路被去重
                store.flush();
            }

            List<Record> metrics = store.query(LogQuery.of(null, null, null,
                    List.of(RecordType.METRICS_SAMPLE), 100));
            assertEquals(4, metrics.size(), "两轮 × 两个组件，各一条指标采样");

            List<Record> events = store.query(LogQuery.of(null, null, null,
                    List.of(RecordType.INSTANCE_EVENT), 100));
            assertEquals(3, events.size(), "组件 gateway+nameserver 各一次 down，加实例 i1 一次 down");

            List<Record> traces = store.query(LogQuery.of(null, null, null,
                    List.of(RecordType.REQUEST_TRACE), 100));
            assertEquals(1, traces.size(), "慢与错误两路同一 trace，按 traceId 去重只写一次");
        }
    }

    @Test
    void instanceRegisterAndRemoveEmitEventsAfterBaseline() throws Exception {
        AdminConfigService service = mock(AdminConfigService.class);
        AdminProperties props = new AdminProperties();
        props.setLogCollectIntervalSeconds(3600);

        when(service.loadStatus()).thenReturn(statusMap(true)); // 组件不翻转
        when(service.loadMetrics()).thenReturn(mapper.readTree("{}"));
        when(service.loadNameserverMetrics()).thenReturn(mapper.readTree("{}"));
        when(service.loadTraces(anyMap())).thenReturn(mapper.readTree("{\"traces\":[]}"));
        when(service.listInstances())
                .thenReturn(instances(Map.of("i1", true)))             // 基线
                .thenReturn(instances(Map.of("i1", true, "i2", true))) // i2 注册
                .thenReturn(instances(Map.of("i1", true)));            // i2 移除

        try (RecordStore store = new JdbcRecordStore("./target/test-logs/collector-reg-" + System.nanoTime())) {
            try (TelemetryCollector collector = new TelemetryCollector(service, store, props)) {
                collector.collect();
                collector.collect();
                collector.collect();
                store.flush();
            }

            List<Record> events = store.query(LogQuery.of(null, null, null,
                    List.of(RecordType.INSTANCE_EVENT), 100));
            assertEquals(2, events.size(), "基线后 i2 注册一次 + 移除一次，共 2 条");
        }
    }

    private Map<String, Object> statusMap(boolean reachable) {
        Map<String, Object> comp = new LinkedHashMap<>();
        comp.put("reachable", reachable);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put(RoverComponent.GATEWAY.id(), comp);
        root.put(RoverComponent.NAMESERVER.id(), comp);
        return root;
    }

    private List<Map<String, Object>> instances(Map<String, Boolean> byId) {
        List<Map<String, Object>> list = new ArrayList<>();
        byId.forEach((id, healthy) -> {
            Map<String, Object> inst = new LinkedHashMap<>();
            inst.put("instanceId", id);
            inst.put("serviceName", "svc");
            inst.put("host", "127.0.0.1");
            inst.put("port", 8080);
            inst.put("healthy", healthy);
            list.add(inst);
        });
        return list;
    }
}
