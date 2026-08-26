package com.rover.gateway.bootstrap.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.gateway.bootstrap.config.GatewayConfig.CircuitBreakerProperties;
import org.junit.jupiter.api.Test;

class GatewayConfigCircuitBreakerValidatorTest {

    @Test
    void defaultCircuitBreakerPasses() {
        assertDoesNotThrow(() -> new GatewayConfig().validate());
    }

    @Test
    void upperCaseRecoveryPasses() {
        GatewayConfig config = new GatewayConfig();
        config.gatewayProperties().getCircuitBreaker().setRecovery("HALF");
        assertDoesNotThrow(config::validate);
    }

    @Test
    void invalidValuesFail() {
        assertTrue(fails(circuit -> circuit.setFailureThreshold(0)));
        assertTrue(fails(circuit -> circuit.setOpenSeconds(0)));
        assertTrue(fails(circuit -> circuit.setOpenSeconds(3601)));
        assertTrue(fails(circuit -> circuit.setRecovery("probe")));
    }

    private static boolean fails(java.util.function.Consumer<CircuitBreakerProperties> mutate) {
        GatewayConfig config = new GatewayConfig();
        mutate.accept(config.gatewayProperties().getCircuitBreaker());
        try {
            config.validate();
            return false;
        } catch (IllegalArgumentException ignored) {
            return true;
        }
    }
}
