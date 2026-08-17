package com.rover.gateway.core.route;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class RouteMatcherTest {

    @Test
    void rootRouteMatchesNormalPaths() {
        RouteConfig root = new RouteConfig();
        root.setId("root");
        root.setBusinessPrefix("/");

        assertEquals("root", new RouteMatcher(List.of(root)).match("/foo").getId());
    }
}
