package com.rover.gateway.bootstrap.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.gateway.core.config.GatewayDefaults;
import com.rover.gateway.core.config.GatewaySystemProperties;
import org.junit.jupiter.api.Test;

class GatewayConfigOptionalDefaultsTest {

    @Test
    void omittedPoolAndInflightUseDefaults() {
        GatewayConfig config = new GatewayConfig();
        assertEquals(GatewayDefaults.MAX_CONNECTIONS_PER_EVENT_LOOP,
                config.getMaxConnectionsPerEventLoopOrDefault());
        assertEquals(GatewayDefaults.MAX_PENDING_ACQUIRES, config.getMaxPendingAcquiresOrDefault());
        assertTrue(config.getMaxInflightOrDefault() >= 64);
        assertEquals(GatewayDefaults.INBOUND_IDLE_TIMEOUT_SECONDS,
                config.getInboundIdleTimeoutSecondsOrDefault());
        assertEquals(GatewayDefaults.OUTBOUND_IDLE_TIMEOUT_SECONDS,
                config.getOutboundIdleTimeoutSecondsOrDefault());
        assertEquals(GatewayDefaults.REQUEST_IDLE_TIMEOUT_SECONDS,
                config.getRequestIdleTimeoutSecondsOrDefault());
        assertDoesNotThrow(config::validate);
    }

    @Test
    void zeroTimeoutsMeanDefault() {
        GatewayConfig config = new GatewayConfig();
        config.gatewayProperties().getProxy().setConnectTimeoutMillis(0);
        config.gatewayProperties().getProxy().setRequestTimeoutMillis(0);
        assertDoesNotThrow(config::validate);
        assertEquals(GatewayDefaults.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeoutMillisOrDefault());
        assertEquals(GatewayDefaults.REQUEST_TIMEOUT_MILLIS, config.getRequestTimeoutMillisOrDefault());
    }

    @Test
    void explicitPoolWins() {
        GatewayConfig config = new GatewayConfig();
        config.gatewayProperties().getProxy().setMaxConnectionsPerEventLoop(8);
        config.gatewayProperties().getProxy().setMaxPendingAcquires(16);
        config.gatewayProperties().getServer().setMaxInflight(32);
        config.gatewayProperties().getServer().setIdleTimeoutSeconds(90);
        config.gatewayProperties().getServer().setRequestIdleTimeoutSeconds(15);
        config.gatewayProperties().getProxy().setIdleTimeoutSeconds(45);
        assertEquals(8, config.getMaxConnectionsPerEventLoopOrDefault());
        assertEquals(16, config.getMaxPendingAcquiresOrDefault());
        assertEquals(32, config.getMaxInflightOrDefault());
        assertEquals(90, config.getInboundIdleTimeoutSecondsOrDefault());
        assertEquals(15, config.getRequestIdleTimeoutSecondsOrDefault());
        assertEquals(45, config.getOutboundIdleTimeoutSecondsOrDefault());
        assertDoesNotThrow(config::validate);
    }

    @Test
    void negativePoolFails() {
        GatewayConfig config = new GatewayConfig();
        config.gatewayProperties().getProxy().setMaxConnectionsPerEventLoop(-1);
        IllegalStateException error = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(error.getMessage().contains("maxConnectionsPerEventLoop"));
    }

    @Test
    void exportWritesOnlyPositiveYaml() {
        String connKey = GatewaySystemProperties.MAX_CONNECTIONS_PER_EVENT_LOOP;
        String old = System.getProperty(connKey);
        try {
            System.clearProperty(connKey);
            GatewayConfig empty = new GatewayConfig();
            empty.exportPositiveOverrides();
            assertEquals(null, System.getProperty(connKey));

            GatewayConfig filled = new GatewayConfig();
            filled.gatewayProperties().getProxy().setMaxConnectionsPerEventLoop(8);
            filled.exportPositiveOverrides();
            assertEquals("8", System.getProperty(connKey));
        } finally {
            if (old == null) {
                System.clearProperty(connKey);
            } else {
                System.setProperty(connKey, old);
            }
        }
    }
}
