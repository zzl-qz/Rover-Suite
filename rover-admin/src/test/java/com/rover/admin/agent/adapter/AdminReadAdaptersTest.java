package com.rover.admin.agent.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rover.admin.service.AdminConfigService;
import com.rover.agent.core.port.SnapshotUnavailableException;
import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.core.snapshot.GatewayMetricSnapshot;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import com.rover.agent.core.snapshot.TraceSnapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 适配器是 Agent 与 Admin 的唯一接触面：字段要如实映射，且绝不触发写操作。 */
@ExtendWith(MockitoExtension.class)
class AdminReadAdaptersTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock
    private AdminConfigService admin;

    @Test
    void mapsRouteListAndDiscoveryMode() {
        when(admin.listRoutes()).thenReturn(List.of(route()));
        when(admin.discoveryType()).thenReturn("nameserver");

        List<RouteSnapshot> routes = new AdminRouteReadAdapter(admin).routes();

        assertEquals(1, routes.size());
        RouteSnapshot route = routes.get(0);
        assertEquals("demo-tt", route.routeId());
        assertEquals("/api/demo/tt", route.businessPrefix());
        assertEquals("demo", route.serviceName());
        assertEquals("全部", route.group());
        assertEquals("http://127.0.0.1:9100", route.targetUrl());
        assertTrue(route.observedAtMillis() > 0);
        assertEquals(DiscoveryMode.NAMESERVER, new AdminRouteReadAdapter(admin).discoveryMode());
    }

    @Test
    void mapsInstanceListWithoutInventingFields() {
        Map<String, Object> full = new LinkedHashMap<>();
        full.put("serviceName", "demo");
        full.put("group", "11");
        full.put("host", "127.0.0.1");
        full.put("port", 9100);
        full.put("healthy", Boolean.TRUE);
        when(admin.listInstances()).thenReturn(List.of(full, Map.of()));

        List<InstanceSnapshot> instances = new AdminInstanceReadAdapter(admin).instances();

        assertEquals(2, instances.size());
        assertEquals(new InstanceSnapshot("demo", "11", "", "127.0.0.1", 9100, true, 0, false, 0L), instances.get(0));
        assertEquals(new InstanceSnapshot("", "", "", "", 0, false, 0, false, 0L), instances.get(1));
    }

    @Test
    void mapsGatewayMetricsWindow() {
        when(admin.loadMetrics()).thenReturn(read("{\"enabled\":true}"));
        when(admin.loadLive(anyInt())).thenReturn(live("""
                {"traffic":{"windowRequests":12,"status":{"5xx":3}},"resources":{"rejects":{"noUpstream":4}}}"""));

        GatewayMetricSnapshot snapshot = new AdminMetricReadAdapter(admin).gatewayWindow(60);

        assertEquals(12, snapshot.windowRequests());
        assertEquals(3, snapshot.status5xx());
        assertEquals(4, snapshot.noUpstreamRejects());
    }

    @Test
    void treatsDisabledMetricsAndUnreachableLiveAsUnavailable() {
        when(admin.loadMetrics()).thenReturn(read("{\"enabled\":false}"));
        AdminMetricReadAdapter adapter = new AdminMetricReadAdapter(admin);
        assertThrows(SnapshotUnavailableException.class, () -> adapter.gatewayWindow(60));

        when(admin.loadMetrics()).thenReturn(read("{\"enabled\":true}"));
        when(admin.loadLive(anyInt())).thenReturn(live("{\"error\":\"读取失败\"}"));
        assertThrows(SnapshotUnavailableException.class, () -> adapter.gatewayWindow(60));
    }

    @Test
    void keepsOnlyExactPathTraces() {
        when(admin.loadTraces(any())).thenReturn(read("""
                {"enabled":true,"sampleRate":0.5,"traces":[
                  {"traceId":"t1","path":"/api/demo/tt","statusCode":503,"startMillis":100},
                  {"traceId":"t2","path":"/api/demo/tt/child","statusCode":200,"startMillis":200}]}"""));

        TraceSnapshot snapshot = new AdminTraceReadAdapter(admin).byPath("/api/demo/tt");

        assertTrue(snapshot.enabled());
        assertEquals(0.5, snapshot.sampleRate());
        assertEquals(1, snapshot.rows().size());
        assertEquals("t1", snapshot.rows().get(0).traceId());
        assertEquals(503, snapshot.rows().get(0).statusCode());
    }

    @Test
    void reportsTraceDisabledAsAValidFact() {
        when(admin.loadTraces(any())).thenReturn(read("{\"enabled\":false,\"traces\":[]}"));

        TraceSnapshot snapshot = new AdminTraceReadAdapter(admin).byPath("/api/demo/tt");

        assertFalse(snapshot.enabled());
        assertTrue(snapshot.rows().isEmpty());
    }

    @Test
    void wrapsDownstreamFailuresAndNeverWrites() {
        when(admin.listRoutes()).thenThrow(new IllegalStateException("读取 Gateway 路由失败"));
        when(admin.listInstances()).thenThrow(new IllegalStateException("读取 Nameserver 实例失败"));
        when(admin.loadMetrics()).thenThrow(new IllegalStateException("读取 Gateway 指标失败"));
        when(admin.loadTraces(any())).thenThrow(new IllegalStateException("读取请求链路失败"));

        assertThrows(SnapshotUnavailableException.class, () -> new AdminRouteReadAdapter(admin).routes());
        assertThrows(SnapshotUnavailableException.class, () -> new AdminInstanceReadAdapter(admin).instances());
        assertThrows(SnapshotUnavailableException.class, () -> new AdminMetricReadAdapter(admin).gatewayWindow(60));
        assertThrows(SnapshotUnavailableException.class, () -> new AdminTraceReadAdapter(admin).byPath("/api/demo/tt"));

        verify(admin, never()).saveRoute(any());
        verify(admin, never()).deleteRoute(anyString(), anyInt());
        verify(admin, never()).updateConfig(anyString(), anyString(), anyString());
    }

    private static Map<String, Object> route() {
        Map<String, Object> route = new LinkedHashMap<>();
        route.put("id", "demo-tt");
        route.put("businessPrefix", "/api/demo/tt");
        route.put("targets", List.of(Map.of("serviceName", "demo", "group", "全部", "weight", 100)));
        route.put("targetUrl", "http://127.0.0.1:9100");
        return route;
    }

    private static JsonNode read(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Map<String, Object> live(String gatewayJson) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("gateway", read(gatewayJson));
        return snapshot;
    }
}