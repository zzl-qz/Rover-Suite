package com.rover.gateway.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.rover.common.config.RuntimeConfigOverlayStore;
import com.rover.common.manage.ManageApiException;
import com.rover.gateway.core.config.GatewayRuntimeConfigApplier;
import com.rover.gateway.core.config.GatewayRuntimeConfigManager;
import com.rover.gateway.core.discovery.DiscoverySettings;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.gateway.core.filter.FilterSettings;
import com.rover.gateway.core.route.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayResourceIdentityTest {
    @TempDir Path directory;

    @Test void restartPreservesUidAndRecreationRejectsOldIdentityWithFreshRevision() {
        RouteOverlayStore store = new RouteOverlayStore(directory.resolve("identity.json"));
        GatewayRuntime first = runtime(store, List.of(route()));
        first.initializeManagedState();
        String uid = first.getRouteMatcher().listRoutes().get(0).getResourceUid();
        assertNotNull(uid);
        var saved = store.loadOrNull();
        GatewayRuntime restarted = runtime(store, saved.routes());
        restarted.restoreRoutesRevision(saved.revision(), saved.appliedOperationId());
        assertEquals(uid, restarted.getRouteMatcher().listRoutes().get(0).getResourceUid());
        restarted.adjustTargetWeight(0, "adjust", "orders", "demo", "v2", 0, uid, 1);
        assertEquals(uid, store.loadOrNull().routes().get(0).getResourceUid());
        restarted.removeRoute(1, "delete", "orders");
        RouteConfig forged = route();
        forged.setResourceUid(uid);
        restarted.addOrReplaceRoute(2, "recreate", forged);
        String newUid = restarted.getRouteMatcher().listRoutes().get(0).getResourceUid();
        assertNotEquals(uid, newUid);
        ManageApiException failure = assertThrows(ManageApiException.class,
                () -> restarted.adjustTargetWeight(3, "old-plan", "orders", "demo", "v2", 0, uid, 1));
        assertEquals("RESOURCE_IDENTITY_MISMATCH", failure.getDetails().get("code"));
        assertEquals(3, restarted.getRevision());
        assertEquals(newUid, store.loadOrNull().routes().get(0).getResourceUid());
        first.close();
        restarted.close();
    }

    @Test void legacyJsonGetsPersistentIdentityWithoutInventingReceipt() {
        RouteOverlayStore store = new RouteOverlayStore(directory.resolve("legacy.json"));
        store.save(4, "legacy", 1, List.of(route()));
        GatewayRuntime runtime = runtime(store, store.loadOrNull().routes());
        runtime.restoreRoutesRevision(4, "legacy");
        runtime.initializeManagedState();
        assertNotNull(store.loadOrNull().routes().get(0).getResourceUid());
        assertEquals(4, store.loadOrNull().revision());
        assertEquals(GatewayRuntime.OperationStatus.UNKNOWN, runtime.operationOf("legacy").status());
        runtime.close();
    }

    private GatewayRuntime runtime(RouteOverlayStore store, List<RouteConfig> routes) {
        DiscoverySettings discovery = new DiscoverySettings();
        discovery.setType(DiscoveryType.NAMESERVER);
        return new GatewayRuntime(0, routes, 1000, 3000, new FilterSettings(), discovery, null,
                new GatewayRuntimeConfigManager(new GatewayRuntimeConfigApplier(),
                        new RuntimeConfigOverlayStore(directory.resolve("runtime-" + System.nanoTime() + ".json"))), "", store);
    }

    private RouteConfig route() {
        RouteConfig route = new RouteConfig();
        route.setId("orders"); route.setBusinessPrefix("/orders");
        route.setTargets(List.of(new RouteTarget("demo", "v1", 95), new RouteTarget("demo", "v2", 5)));
        return route;
    }
}
