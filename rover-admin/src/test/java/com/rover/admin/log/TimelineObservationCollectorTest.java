package com.rover.admin.log;

import com.rover.admin.service.AdminConfigService;
import com.rover.agent.core.model.*;
import com.rover.agent.core.port.RouteControlPort;
import com.rover.agent.core.timeline.TimelineQuery;
import com.rover.agent.runtime.repository.AgentStore;
import com.rover.agent.runtime.timeline.ChangeTimelineService;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TimelineObservationCollectorTest {
    @TempDir Path directory;
    @Test void pollingPreservesIdentityRedactsUrlsAndSeparatesUnavailableSources() throws Exception {
        AdminConfigService admin = mock(AdminConfigService.class);
        RouteControlPort control = mock(RouteControlPort.class);
        ResourceScope scope = new ResourceScope("org", "team", "test");
        when(control.resourceScope()).thenReturn(scope); when(control.resourceNamespace()).thenReturn("timeline"); when(control.controlTargetId()).thenReturn("gateway");
        Map<String, Object> route = new LinkedHashMap<>(Map.of("id", "orders", "businessPrefix", "/orders", "resourceUid", "route-uid", "generation", 3,
                "targets", List.of(Map.of("serviceName", "orders", "group", "v1", "weight", 90), Map.of("serviceName", "orders", "group", "v2", "weight", 10)),
                "targetUrl", "http://fixture-user:fixture-password@localhost:8080/path?token=fixture-value"));
        when(admin.routesState()).thenAnswer(call -> Map.of("routes", List.of(new LinkedHashMap<>(route))));
        Map<String, Object> instance = new LinkedHashMap<>(Map.of("instanceId", "i", "serviceName", "orders", "group", "v2", "host", "localhost", "port", 8080, "healthy", true, "weight", 10, "ephemeral", true));
        when(admin.listInstances()).thenAnswer(call -> List.of(new LinkedHashMap<>(instance)));
        when(admin.listConfigs()).thenReturn(List.of(Map.of("component", "gateway", "key", "gateway.request.timeoutMillis", "value", "2000"),
                Map.of("component", "gateway", "key", "gateway.plugin.secretToken", "value", "fixture-secret-plugin-value"),
                Map.of("component", "gateway", "key", "gateway.request.password", "value", "fixture-secret-unknown-setting"),
                Map.of("component", "nameserver", "key", "nameserver.push.enabled", "value", "true")));
        try (AgentStore store = AgentStore.file(directory.resolve("collector").toString())) {
            var timeline = new ChangeTimelineService(store, control, 5000);
            var collector = new TimelineObservationCollector(admin, timeline);
            long from = System.currentTimeMillis() - 1000;
            collector.collect();
            route.put("targets", List.of(Map.of("serviceName", "orders", "group", "v1", "weight", 90), Map.of("serviceName", "orders", "group", "v2", "weight", 50)));
            instance.put("weight", 20);
            collector.collect();
            route.put("targets", List.of(Map.of("serviceName", "orders", "group", "v2", "weight", 50), Map.of("serviceName", "orders", "group", "v1", "weight", 90)));
            route.put("stickyHeader", "X-New-Sticky-Key");
            when(admin.listInstances()).thenThrow(new IllegalStateException("offline"));
            when(admin.listConfigs()).thenReturn(List.of(Map.of("component", "gateway", "key", "gateway.request.timeoutMillis", "value", "3000"),
                    Map.of("component", "nameserver", "key", "nameserver.*", "error", true)));
            collector.collect();
            var range = new TimeRange(from, System.currentTimeMillis() + 1000);
            var context = new InvestigationContext(null, null, scope, "gateway", "timeline", null, range, Map.of(), "RBAC");
            var result = timeline.query(new TimelineQuery(context, ResourceTarget.route("/orders/checkout"), range, 100));
            assertTrue(result.events().stream().anyMatch(e -> e.kind().equals("ROUTE_CHANGED") && e.subject().routeUid().equals("route-uid") && e.subject().generation() == 3));
            assertTrue(result.events().stream().anyMatch(e -> e.kind().equals("CONFIG_CHANGED")));
            assertTrue(result.events().stream().anyMatch(e -> e.kind().equals("ROUTE_CHANGED") && e.facts().get("after") instanceof Map<?, ?> after
                    && "X-New-Sticky-Key".equals(after.get("stickyHeader")) && after.get("targets") instanceof List<?> targets
                    && ((Map<?, ?>) targets.get(0)).get("group").equals("v2")));
            assertTrue(result.events().stream().anyMatch(e -> e.kind().equals("INSTANCE_CHANGED") && e.facts().get("after") instanceof Map<?, ?> after
                    && ((Number) after.get("weight")).intValue() == 20));
            assertFalse(result.events().stream().anyMatch(e -> e.kind().equals("INSTANCE_REMOVED")));
            String stored = result.events().toString();
            assertFalse(stored.contains("fixture-password")); assertFalse(stored.contains("fixture-value")); assertFalse(stored.contains("fixture-secret-plugin-value"));
            assertFalse(stored.contains("fixture-secret-unknown-setting"));
            assertTrue(stored.contains("http://localhost:8080/path"));
            assertTrue(result.sources().stream().anyMatch(s -> s.get("sourceId").equals("rover.instances") && s.get("available").equals(false)));
            assertTrue(result.sources().stream().anyMatch(s -> s.get("sourceId").equals("rover.config.gateway") && s.get("available").equals(true)));
            assertTrue(result.sources().stream().anyMatch(s -> s.get("sourceId").equals("rover.config.nameserver") && s.get("available").equals(false)));
            verify(control, never()).adjustTargetWeight(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyString());
        }
    }
}
