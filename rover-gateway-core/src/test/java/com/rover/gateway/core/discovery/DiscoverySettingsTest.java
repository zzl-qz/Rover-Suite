package com.rover.gateway.core.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.gateway.core.route.RouteConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiscoverySettingsTest {

    @Test
    void staticDefaultsIsStatic() {
        assertEquals(DiscoveryType.STATIC, DiscoverySettings.staticDefaults().getType());
    }

    @Test
    void subscribeSpecsFromDedupesServiceAndGroup() {
        RouteConfig first = new RouteConfig();
        first.setServiceName("demo");
        first.setGroup("DEFAULT_GROUP");

        RouteConfig same = new RouteConfig();
        same.setServiceName("demo");
        same.setGroup("DEFAULT_GROUP");

        RouteConfig other = new RouteConfig();
        other.setServiceName("demo");
        other.setGroup("other");

        RouteConfig skipped = new RouteConfig();
        skipped.setServiceName("  ");

        List<RouteConfig> routes = new ArrayList<>();
        routes.add(first);
        routes.add(same);
        routes.add(other);
        routes.add(skipped);
        routes.add(null);
        List<DiscoverySettings.ServiceSubscribeSpec> specs = DiscoverySettings.subscribeSpecsFrom(routes);

        assertEquals(2, specs.size());
        assertEquals("demo", specs.get(0).getServiceName());
        assertEquals("DEFAULT_GROUP", specs.get(0).getGroup());
        assertEquals("other", specs.get(1).getGroup());
    }

    @Test
    void subscribeSpecsFromEmptyIsEmpty() {
        assertTrue(DiscoverySettings.subscribeSpecsFrom(null).isEmpty());
        assertTrue(DiscoverySettings.subscribeSpecsFrom(List.of()).isEmpty());
    }
}
