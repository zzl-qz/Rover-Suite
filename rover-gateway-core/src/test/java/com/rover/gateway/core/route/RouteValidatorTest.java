package com.rover.gateway.core.route;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.gateway.core.discovery.DiscoveryType;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteValidatorTest {

    @Test
    void nameserverAllowsStaticRoute() {
        RouteConfig route = staticRoute("/api/static");
        List<RouteConfig> normalized = new RouteValidator(DiscoveryType.NAMESERVER)
                .normalizeAndValidate(List.of(route));
        assertEquals(1, normalized.size());
        assertEquals("/api/static", normalized.get(0).getBusinessPrefix());
    }

    @Test
    void nacosAllowsStaticRoute() {
        assertDoesNotThrow(() -> new RouteValidator(DiscoveryType.NACOS)
                .normalizeAndValidate(List.of(staticRoute("/api/legacy"))));
    }

    @Test
    void bothServiceNameAndTargetUrlsFail() {
        RouteConfig route = staticRoute("/api/mix");
        route.setServiceName("demo-service");
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new RouteValidator(DiscoveryType.NAMESERVER).normalizeAndValidate(List.of(route)));
        assertTrue(error.getMessage().contains("不能同时写"));
    }

    @Test
    void nameserverNeedsServiceNameOrStatic() {
        RouteConfig route = new RouteConfig();
        route.setBusinessPrefix("/api/empty");
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new RouteValidator(DiscoveryType.NAMESERVER).normalizeAndValidate(List.of(route)));
        assertTrue(error.getMessage().contains("serviceName"));
        assertTrue(error.getMessage().contains("targetUrl"));
    }

    @Test
    void staticModeStillRequiresAddress() {
        RouteConfig route = new RouteConfig();
        route.setBusinessPrefix("/api/static");
        route.setServiceName("demo-service");
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new RouteValidator(DiscoveryType.STATIC).normalizeAndValidate(List.of(route)));
        assertTrue(error.getMessage().contains("static 模式需要"));
    }

    private static RouteConfig staticRoute(String prefix) {
        RouteConfig route = new RouteConfig();
        route.setId("static-demo");
        route.setBusinessPrefix(prefix);
        route.setTargetUrls(List.of("http://127.0.0.1:8081"));
        return route;
    }
}
