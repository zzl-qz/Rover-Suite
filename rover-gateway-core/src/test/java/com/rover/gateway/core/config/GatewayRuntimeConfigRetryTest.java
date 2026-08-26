package com.rover.gateway.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GatewayRuntimeConfigRetryTest {

    @Test
    void retryEnabledAcceptsBooleanAndRejectsGarbage() {
        GatewayRuntimeConfigManager manager = new GatewayRuntimeConfigManager();
        manager.seed(GatewayRuntimeConfigKeys.RETRY_ENABLED, "TRUE");
        assertEquals(
                "true",
                manager.listConfigs().stream()
                        .filter(item -> GatewayRuntimeConfigKeys.RETRY_ENABLED.equals(item.getKey()))
                        .findFirst()
                        .orElseThrow()
                        .getValue());
        assertThrows(IllegalArgumentException.class,
                () -> manager.seed(GatewayRuntimeConfigKeys.RETRY_ENABLED, "maybe"));
        assertTrue(manager.listConfigs().stream()
                .anyMatch(item -> GatewayRuntimeConfigKeys.RETRY_ENABLED.equals(item.getKey())));
    }
}
