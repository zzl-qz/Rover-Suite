package com.rover.gateway.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.gateway.core.filter.circuit.CircuitBreakerSettings;
import org.junit.jupiter.api.Test;

class GatewayRuntimeConfigCircuitTest {

    @Test
    void recoveryAcceptsUpperCaseThenStoresLowerCase() {
        GatewayRuntimeConfigManager manager = new GatewayRuntimeConfigManager();
        manager.seed(GatewayRuntimeConfigKeys.CIRCUIT_BREAKER_RECOVERY, "HALF");
        assertEquals(
                CircuitBreakerSettings.HALF,
                manager.listConfigs().stream()
                        .filter(item -> GatewayRuntimeConfigKeys.CIRCUIT_BREAKER_RECOVERY.equals(item.getKey()))
                        .findFirst()
                        .orElseThrow()
                        .getValue());
    }

    @Test
    void rejectsInvalidCircuitValues() {
        GatewayRuntimeConfigManager manager = new GatewayRuntimeConfigManager();
        assertThrows(IllegalArgumentException.class,
                () -> manager.seed(GatewayRuntimeConfigKeys.CIRCUIT_BREAKER_FAILURE_THRESHOLD, "0"));
        assertThrows(IllegalArgumentException.class,
                () -> manager.seed(GatewayRuntimeConfigKeys.CIRCUIT_BREAKER_OPEN_SECONDS, "0"));
        assertThrows(IllegalArgumentException.class,
                () -> manager.seed(GatewayRuntimeConfigKeys.CIRCUIT_BREAKER_OPEN_SECONDS, "3601"));
        assertThrows(IllegalArgumentException.class,
                () -> manager.seed(GatewayRuntimeConfigKeys.CIRCUIT_BREAKER_RECOVERY, "probe"));
    }

    @Test
    void acceptsBoundaryOpenSeconds() {
        GatewayRuntimeConfigManager manager = new GatewayRuntimeConfigManager();
        manager.seed(GatewayRuntimeConfigKeys.CIRCUIT_BREAKER_OPEN_SECONDS, "1");
        manager.seed(GatewayRuntimeConfigKeys.CIRCUIT_BREAKER_OPEN_SECONDS, "3600");
        manager.seed(GatewayRuntimeConfigKeys.CIRCUIT_BREAKER_FAILURE_THRESHOLD, "1");
        assertTrue(manager.listConfigs().stream()
                .anyMatch(item -> GatewayRuntimeConfigKeys.CIRCUIT_BREAKER_OPEN_SECONDS.equals(item.getKey())
                        && "3600".equals(item.getValue())));
    }
}
