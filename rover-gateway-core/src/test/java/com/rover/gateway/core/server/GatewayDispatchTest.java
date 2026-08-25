package com.rover.gateway.core.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.gateway.core.config.GatewaySystemProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GatewayDispatchTest {

    @AfterEach
    void clearProp() {
        System.clearProperty(GatewaySystemProperties.DISPATCH_ON_EVENT_LOOP);
    }

    @Test
    void defaultsToBizPool() {
        System.clearProperty(GatewaySystemProperties.DISPATCH_ON_EVENT_LOOP);
        assertFalse(GatewayDispatch.onEventLoop());
    }

    @Test
    void canForceEventLoop() {
        System.setProperty(GatewaySystemProperties.DISPATCH_ON_EVENT_LOOP, "true");
        assertTrue(GatewayDispatch.onEventLoop());
    }

    @Test
    void canForceBizPool() {
        System.setProperty(GatewaySystemProperties.DISPATCH_ON_EVENT_LOOP, "false");
        assertFalse(GatewayDispatch.onEventLoop());
    }
}
