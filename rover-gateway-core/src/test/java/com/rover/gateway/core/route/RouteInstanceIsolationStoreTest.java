package com.rover.gateway.core.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RouteInstanceIsolationStoreTest {
    @TempDir Path tempDir;

    @Test
    void isolationIsDurableScopedAndExpiresIntoReviewWithoutRestoringTraffic() {
        Path routes = tempDir.resolve("routes.json");
        var key = key("route-a", 1);
        RouteInstanceIsolationStore first = new RouteInstanceIsolationStore(routes);
        var isolated = first.isolate(key, RouteInstanceIsolationStore.Source.MANUAL, "oncall", "bad responses",
                "healthy instances=3", 2, 20_000, 0, "isolate-1", 10_000);

        RouteInstanceIsolationStore restarted = new RouteInstanceIsolationStore(routes);
        assertEquals("ISOLATED", restarted.active("uid-a", 1, "route-a", "orders", "v1").get(0).state().name());
        assertTrue(restarted.active("uid-a", 1, "other-route", "orders", "v1").isEmpty());
        assertEquals("EXPIRED_PENDING_REVIEW", restarted.list().get(0).lifecycle(20_000));
        assertEquals(1, isolated.version());
    }

    @Test
    void operationReplayIsIdempotentAndCannotChangeIntent() {
        RouteInstanceIsolationStore store = new RouteInstanceIsolationStore(tempDir.resolve("routes.json"));
        var key = key("route-a", 1);
        var first = store.isolate(key, RouteInstanceIsolationStore.Source.MANUAL, "oncall", "bad responses",
                "healthy instances=3", 2, 20_000, 0, "same-op", 10_000);
        var replay = store.isolate(key, RouteInstanceIsolationStore.Source.MANUAL, "oncall", "bad responses",
                "healthy instances=3", 2, 20_000, 0, "same-op", 11_000);
        assertEquals(first, replay);
        assertThrows(IllegalStateException.class, () -> store.isolate(key, RouteInstanceIsolationStore.Source.MANUAL,
                "oncall", "different intent", "healthy instances=3", 2, 20_000, 0, "same-op", 11_000));
    }

    @Test
    void agentCannotOverrideManualIsolationAndRestoreUsesVersionAndSource() {
        RouteInstanceIsolationStore store = new RouteInstanceIsolationStore(tempDir.resolve("routes.json"));
        var key = key("route-a", 1);
        store.isolate(key, RouteInstanceIsolationStore.Source.MANUAL, "oncall", "manual hold",
                "healthy instances=3", 2, 20_000, 0, "manual-op", 10_000);
        assertThrows(IllegalStateException.class, () -> store.isolate(key, RouteInstanceIsolationStore.Source.AGENT,
                "agent", "auto hold", "healthy instances=3", 2, 21_000, 1, "agent-op", 11_000));
        assertThrows(IllegalStateException.class, () -> store.restore(key, RouteInstanceIsolationStore.Source.AGENT,
                "agent", 1, "three healthy", 2, "agent-restore", 12_000));
        assertThrows(IllegalStateException.class, () -> store.restore(key, RouteInstanceIsolationStore.Source.MANUAL,
                "oncall", 2, "three healthy", 2, "stale-restore", 12_000));
        var restored = store.restore(key, RouteInstanceIsolationStore.Source.MANUAL, "oncall", 1,
                "three healthy", 2, "restore-op", 12_000);
        assertEquals(RouteInstanceIsolationStore.State.RESTORED, restored.state());
        assertTrue(store.active("uid-a", 1, "route-a", "orders", "v1").isEmpty());
        assertFalse(store.list().get(0).active());
    }

    @Test
    void routeGenerationIsPartOfIsolationScope() {
        RouteInstanceIsolationStore store = new RouteInstanceIsolationStore(tempDir.resolve("routes.json"));
        store.isolate(key("route-a", 1), RouteInstanceIsolationStore.Source.MANUAL, "oncall", "bad responses",
                "healthy instances=3", 2, 20_000, 0, "gen-1", 10_000);
        assertTrue(store.active("uid-a", 2, "route-a", "orders", "v1").isEmpty());
        assertEquals(1, store.active("uid-a", 1, "route-a", "orders", "v1").size());
    }

    @Test
    void emptyGroupListsIsolationsAcrossAllServiceGroups() {
        RouteInstanceIsolationStore store = new RouteInstanceIsolationStore(tempDir.resolve("all-groups.json"));
        store.isolate(new RouteInstanceIsolationStore.Key("uid-a", 1, "route-a", "orders", "blue",
                        "orders-blue", "10.0.0.1", 8080), RouteInstanceIsolationStore.Source.MANUAL,
                "oncall", "failed health check", "capacity reviewed", 1, 20_000, 0, "blue-op", 10_000);
        assertEquals(1, store.active("uid-a", 1, "route-a", "orders", "").size());
    }

    @Test
    void restoringLastIsolatedInstanceIsAllowedWhenItMeetsTheDeclaredHealthyMinimum() {
        RouteInstanceIsolationStore store = new RouteInstanceIsolationStore(tempDir.resolve("routes.json"));
        var key = key("route-a", 1);
        store.isolate(key, RouteInstanceIsolationStore.Source.MANUAL, "oncall", "single-node maintenance",
                "only instance is healthy", 1, 20_000, 0, "isolate-only", 10_000);

        var restored = store.restore(key, RouteInstanceIsolationStore.Source.MANUAL, "oncall", 1,
                "instance health rechecked", 1, "restore-only", 12_000);

        assertEquals(RouteInstanceIsolationStore.State.RESTORED, restored.state());
        assertTrue(store.active("uid-a", 1, "route-a", "orders", "v1").isEmpty());
    }

    private static RouteInstanceIsolationStore.Key key(String routeId, long generation) {
        return new RouteInstanceIsolationStore.Key("uid-a", generation, routeId, "orders", "v1",
                "orders-1", "10.0.0.1", 8080);
    }
}
