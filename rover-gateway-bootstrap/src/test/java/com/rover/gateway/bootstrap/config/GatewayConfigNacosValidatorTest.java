package com.rover.gateway.bootstrap.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.gateway.bootstrap.config.GatewayConfig.RouteProperties;
import org.junit.jupiter.api.Test;

class GatewayConfigNacosValidatorTest {

    @Test
    void nacosRequiresServerAddrAndServiceName() {
        GatewayConfig config = nacosConfig("127.0.0.1:8848", "demo-service");
        assertDoesNotThrow(config::validate);
    }

    @Test
    void blankServerAddrFails() {
        GatewayConfig config = nacosConfig(" ", "demo-service");
        IllegalStateException error = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(error.getMessage().contains("serverAddr"));
    }

    @Test
    void blankServiceNameFails() {
        GatewayConfig config = nacosConfig("127.0.0.1:8848", "");
        IllegalStateException error = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(error.getMessage().contains("serviceName"));
    }

    private static GatewayConfig nacosConfig(String serverAddr, String serviceName) {
        GatewayConfig config = new GatewayConfig();
        config.gatewayProperties().getDiscovery().setType("nacos");
        config.gatewayProperties().getDiscovery().getNacos().setServerAddr(serverAddr);
        RouteProperties route = new RouteProperties();
        route.setId("nacos-demo");
        route.setBusinessPrefix("/api/nacos");
        route.setServiceName(serviceName);
        config.gatewayProperties().getRoutes().add(route);
        return config;
    }
}
