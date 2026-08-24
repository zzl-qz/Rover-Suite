package com.rover.gateway.core.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class RouteMatcherTest {

    @Test
    void rootRouteMatchesNormalPaths() {
        RouteConfig root = route("root", "/");
        assertEquals("root", new RouteMatcher(List.of(root)).match("/foo").getId());
    }

    @Test
    void longerPrefixWinsOverShorter() {
        RouteConfig api = route("api", "/api");
        RouteConfig user = route("user", "/api/user");
        RouteMatcher matcher = new RouteMatcher(List.of(api, user));
        assertEquals("user", matcher.match("/api/user/1").getId());
        assertEquals("api", matcher.match("/api/orders").getId());
    }

    @Test
    void unmatchedPathReturnsNull() {
        RouteConfig api = route("api", "/api");
        assertNull(new RouteMatcher(List.of(api)).match("/health"));
    }

    @Test
    void trailingSlashOnPrefixStillMatches() {
        RouteConfig api = route("api", "/api/");
        assertEquals("api", new RouteMatcher(List.of(api)).match("/api/x").getId());
        assertEquals("api", new RouteMatcher(List.of(api)).match("/api").getId());
    }

    private static RouteConfig route(String id, String prefix) {
        RouteConfig config = new RouteConfig();
        config.setId(id);
        config.setBusinessPrefix(prefix);
        return config;
    }
}
