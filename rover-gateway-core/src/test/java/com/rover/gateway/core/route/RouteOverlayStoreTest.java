package com.rover.gateway.core.route;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RouteOverlayStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void routeFieldsRoundTripThroughOverlayContract() {
        RouteConfig route = new RouteConfig();
        route.setId("route-1");
        route.setBusinessPrefix("/api");
        route.setTargetUrl("http://127.0.0.1:8080");
        route.setTargetUrls(List.of("http://127.0.0.1:8081|2"));
        route.setServiceName("demo");
        route.setGroup("blue");
        route.setStripPrefix("/api");

        RouteOverlayStore store = new RouteOverlayStore(tempDir.resolve("routes.json"));
        store.save(List.of(route));
        RouteConfig loaded = store.loadOrEmpty().get(0);

        assertEquals(route.getId(), loaded.getId());
        assertEquals(route.getBusinessPrefix(), loaded.getBusinessPrefix());
        assertEquals(route.getTargetUrl(), loaded.getTargetUrl());
        assertEquals(route.getTargetUrls(), loaded.getTargetUrls());
        assertEquals(route.getServiceName(), loaded.getServiceName());
        assertEquals(route.getGroup(), loaded.getGroup());
        assertEquals(route.getStripPrefix(), loaded.getStripPrefix());
    }
}
