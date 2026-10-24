package com.rover.gateway.core.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.common.model.ServiceInstance;
import com.rover.gateway.core.route.RouteConfig;
import com.rover.gateway.core.route.RouteInstanceIsolationStore;
import com.rover.gateway.core.route.RouteTarget;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RouteInstanceIsolationFilterTest {
    @TempDir Path tempDir;

    @Test
    void activeIsolationIsRemovedFromLiveRouteCandidatesOnlyForItsExactRouteGeneration() {
        RouteConfig route = new RouteConfig();
        route.setId("orders-api");
        route.setResourceUid("uid-a");
        route.setGeneration(4);
        route.setTargets(new ArrayList<>(List.of(new RouteTarget("orders", "blue", 100))));
        ServiceInstance isolated = instance("orders-1", "10.0.0.1");
        ServiceInstance healthy = instance("orders-2", "10.0.0.2");
        RouteInstanceIsolationStore store = new RouteInstanceIsolationStore(tempDir.resolve("routes.json"));
        store.isolate(new RouteInstanceIsolationStore.Key("uid-a", 4, "orders-api", "orders", "blue",
                        "orders-1", "10.0.0.1", 8080), RouteInstanceIsolationStore.Source.MANUAL,
                "oncall", "failed health check", "one healthy peer remains", 1, 20_000, 0, "iso-1", 10_000);
        RouteAndProxyFilter filter = new RouteAndProxyFilter(null, null, null, null, null, null, null, null, store);

        assertEquals(List.of(healthy), filter.applyIsolation(route, route.getTargets().get(0), List.of(isolated, healthy)));
        route.setGeneration(5);
        assertEquals(List.of(isolated, healthy), filter.applyIsolation(route, route.getTargets().get(0), List.of(isolated, healthy)),
                "新代次不继承旧路由的隔离");
    }

    @Test
    void reusedInstanceIdAtANewAddressDoesNotInheritOldIsolation() {
        RouteConfig route = new RouteConfig();
        route.setId("orders-api"); route.setResourceUid("uid-a"); route.setGeneration(4);
        route.setTargets(new ArrayList<>(List.of(new RouteTarget("orders", "blue", 100))));
        ServiceInstance moved = instance("orders-1", "10.0.0.9");
        RouteInstanceIsolationStore store = new RouteInstanceIsolationStore(tempDir.resolve("reused.json"));
        store.isolate(new RouteInstanceIsolationStore.Key("uid-a", 4, "orders-api", "orders", "blue",
                        "orders-1", "10.0.0.1", 8080), RouteInstanceIsolationStore.Source.MANUAL,
                "oncall", "old endpoint failed", "checked capacity", 1, 20_000, 0, "old-instance", 10_000);
        RouteAndProxyFilter filter = new RouteAndProxyFilter(null, null, null, null, null, null, null, null, store);

        assertEquals(List.of(moved), filter.applyIsolation(route, route.getTargets().get(0), List.of(moved)));
    }

    @Test
    void unreadableIsolationOverlayFailsClosedEvenWhenLastSnapshotHadNoActiveRows() throws Exception {
        RouteConfig route = new RouteConfig();
        route.setId("orders-api"); route.setResourceUid("uid-a"); route.setGeneration(4);
        route.setTargets(new ArrayList<>(List.of(new RouteTarget("orders", "blue", 100))));
        ServiceInstance healthy = instance("orders-1", "10.0.0.1");
        RouteInstanceIsolationStore store = new RouteInstanceIsolationStore(tempDir.resolve("unreadable.json"));
        Field readable = RouteInstanceIsolationStore.class.getDeclaredField("readable");
        readable.setAccessible(true); readable.setBoolean(store, false);
        RouteAndProxyFilter filter = new RouteAndProxyFilter(null, null, null, null, null, null, null, null, store);

        assertTrue(filter.applyIsolation(route, route.getTargets().get(0), List.of(healthy)).isEmpty(),
                "无法确认持久隔离状态时不得将发现实例放回流量");
    }

    private static ServiceInstance instance(String id, String host) {
        ServiceInstance instance = new ServiceInstance();
        instance.setServiceName("orders");
        instance.setGroup("blue");
        instance.setInstanceId(id);
        instance.setHost(host);
        instance.setPort(8080);
        return instance;
    }
}
