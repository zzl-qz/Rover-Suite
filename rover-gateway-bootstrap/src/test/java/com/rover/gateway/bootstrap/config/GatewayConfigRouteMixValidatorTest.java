package com.rover.gateway.bootstrap.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.gateway.bootstrap.config.GatewayConfig.RouteProperties;
import com.rover.gateway.core.route.RouteConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class GatewayConfigRouteMixValidatorTest {

    @Test
    void nameserverAllowsStaticRoute() {
        GatewayConfig config = nameserverConfig();
        config.gatewayProperties().getRoutes().add(staticRoute("/api/static"));
        assertDoesNotThrow(config::validate);
        List<RouteConfig> mapped = GatewayConfigMapper.toRouteConfigs(config);
        assertEquals(1, mapped.size());
        assertEquals("http://127.0.0.1:8081", mapped.get(0).getTargetUrls().get(0));
    }

    @Test
    void nacosAllowsStaticRoute() {
        GatewayConfig config = nacosConfig();
        config.gatewayProperties().getRoutes().add(staticRoute("/api/legacy"));
        assertDoesNotThrow(config::validate);
        assertEquals(1, GatewayConfigMapper.toRouteConfigs(config).size());
    }

    @Test
    void bothServiceNameAndTargetUrlsFail() {
        GatewayConfig config = nameserverConfig();
        RouteProperties route = staticRoute("/api/mix");
        route.setServiceName("demo-service");
        config.gatewayProperties().getRoutes().add(route);
        IllegalStateException error = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(error.getMessage().contains("不能同时写"));
    }

    @Test
    void nameserverBlankRouteFails() {
        GatewayConfig config = nameserverConfig();
        RouteProperties route = new RouteProperties();
        route.setId("empty");
        route.setBusinessPrefix("/api/empty");
        config.gatewayProperties().getRoutes().add(route);
        IllegalStateException error = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(error.getMessage().contains("serviceName"));
    }

    @Test
    void nameserverKeepsDiscoveryAndStaticTogether() {
        GatewayConfig config = nameserverConfig();
        RouteProperties named = new RouteProperties();
        named.setId("demo-api");
        named.setBusinessPrefix("/api");
        named.setServiceName("demo-service");
        config.gatewayProperties().getRoutes().add(named);
        config.gatewayProperties().getRoutes().add(staticRoute("/api/static"));
        assertDoesNotThrow(config::validate);
        assertEquals(2, GatewayConfigMapper.toRouteConfigs(config).size());
    }

    private static GatewayConfig nameserverConfig() {
        GatewayConfig config = new GatewayConfig();
        config.gatewayProperties().getDiscovery().setType("nameserver");
        config.gatewayProperties().getDiscovery().getNameserver().setAddress("127.0.0.1:8888");
        return config;
    }

    private static GatewayConfig nacosConfig() {
        GatewayConfig config = new GatewayConfig();
        config.gatewayProperties().getDiscovery().setType("nacos");
        config.gatewayProperties().getDiscovery().getNacos().setServerAddr("127.0.0.1:8848");
        return config;
    }

    private static RouteProperties staticRoute(String prefix) {
        RouteProperties route = new RouteProperties();
        route.setId("static-" + prefix.replace('/', '-'));
        route.setBusinessPrefix(prefix);
        route.setTargetUrls(List.of("http://127.0.0.1:8081"));
        return route;
    }
}
