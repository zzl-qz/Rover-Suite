package com.rover.agent.core.investigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.rover.agent.core.snapshot.RouteSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteMatcherTest {

    @Test
    void longestPrefixWins() {
        List<RouteSnapshot> routes = List.of(
                route("/api", "demo-service", ""),
                route("/api/demo/tt", "demo", "11"));

        assertEquals("/api/demo/tt", RouteMatcher.match(routes, "/api/demo/tt").businessPrefix());
        assertEquals("demo-service", RouteMatcher.match(routes, "/api/hello").serviceName());
    }

    @Test
    void slashMatchesEverythingButLosesToMoreSpecificPrefix() {
        List<RouteSnapshot> routes = List.of(route("/", "fallback", ""), route("/api", "demo", ""));

        assertEquals("fallback", RouteMatcher.match(routes, "/whatever").serviceName());
        assertEquals("demo", RouteMatcher.match(routes, "/api/x").serviceName());
    }

    @Test
    void prefixDoesNotMatchPartialSegmentOrQueryString() {
        List<RouteSnapshot> routes = List.of(route("/api/demo", "demo", ""));

        assertNull(RouteMatcher.match(routes, "/api/demolition"));
        assertNull(RouteMatcher.match(routes, "/api/demo-other"));
    }

    @Test
    void blankPrefixIsIgnoredAndMissingRouteListReturnsNull() {
        assertNull(RouteMatcher.match(List.of(route("", "demo", "")), "/api/demo"));
        assertNull(RouteMatcher.match(List.of(), "/api/demo"));
    }

    private static RouteSnapshot route(String prefix, String serviceName, String group) {
        return new RouteSnapshot("id-" + prefix, prefix, serviceName, group, "", "", 1L);
    }
}