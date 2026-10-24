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

    @Test void editingExistingRouteAdvancesGenerationAndNewRouteDoesNotInheritClientGeneration() {
        RouteOverlayStore store = new RouteOverlayStore(directory.resolve("generation.json"));
        GatewayRuntime runtime = runtime(store, List.of(route()));
        RouteConfig original = runtime.getRouteMatcher().listRoutes().get(0);
        assertEquals(1, original.getGeneration());

        RouteConfig edited = route();
        edited.setBusinessPrefix("/orders-v2");
        runtime.applyRoutes(0, "edit-route", List.of(edited));
        RouteConfig changed = runtime.getRouteMatcher().listRoutes().get(0);
        assertEquals(original.getResourceUid(), changed.getResourceUid());
        assertEquals(2, changed.getGeneration(), "改变同一资源配置后代次递增，使旧隔离不再作用于新快照");

        RouteConfig forged = route();
        forged.setResourceUid(original.getResourceUid());
        forged.setGeneration(99);
        runtime.removeRoute(1, "delete-route", "orders");
        runtime.addOrReplaceRoute(2, "recreate-route", forged);
        RouteConfig recreated = runtime.getRouteMatcher().listRoutes().get(0);
        assertNotEquals(original.getResourceUid(), recreated.getResourceUid());
        assertEquals(1, recreated.getGeneration());
        runtime.close();
    }

    @Test void activeIsolationBlocksRouteIdentityChangesUntilRestored() {
        RouteOverlayStore store = new RouteOverlayStore(directory.resolve("isolated-generation.json"));
        GatewayRuntime runtime = runtime(store, List.of(route()));
        RouteConfig original = runtime.getRouteMatcher().listRoutes().get(0);
        var key = new RouteInstanceIsolationStore.Key(original.getResourceUid(), original.getGeneration(),
                original.getId(), "demo", "v1", "orders-1", "10.0.0.1", 8080);
        runtime.getInstanceIsolationStore().isolate(key, RouteInstanceIsolationStore.Source.MANUAL,
                "oncall", "investigating", "capacity checked", 1, System.currentTimeMillis() + 60_000,
                0, "iso-route-edit", System.currentTimeMillis());

        RouteConfig edited = RouteValidator.copyRoute(original);
        edited.setBusinessPrefix("/orders-v2");
        ManageApiException error = assertThrows(ManageApiException.class,
                () -> runtime.applyRoutes(0, "edit-while-isolated", List.of(edited)));
        assertEquals("ACTIVE_INSTANCE_ISOLATION", error.getDetails().get("code"));
        assertEquals(1, runtime.getRouteMatcher().listRoutes().get(0).getGeneration());

        runtime.getInstanceIsolationStore().restore(key, RouteInstanceIsolationStore.Source.MANUAL,
                "oncall", 1, "capacity checked", 1, "restore-route-edit", System.currentTimeMillis());
        runtime.applyRoutes(0, "edit-after-restore", List.of(edited));
        assertEquals(2, runtime.getRouteMatcher().listRoutes().get(0).getGeneration());
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
