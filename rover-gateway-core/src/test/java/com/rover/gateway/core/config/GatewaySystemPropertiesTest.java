package com.rover.gateway.core.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GatewaySystemPropertiesTest {

    @Test
    void nettyAllowsHttpOnly() {
        assertFalse(GatewaySystemProperties.allowsHttpsUpstream("netty"));
        assertDoesNotThrow(() -> GatewaySystemProperties.requireUpstreamScheme(
                "http", "http://a:80", "netty"));
        IllegalArgumentException https = assertThrows(
                IllegalArgumentException.class,
                () -> GatewaySystemProperties.requireUpstreamScheme(
                        "https", "https://a:443", "netty"));
        assertTrue(https.getMessage().contains("proxy.outbound=jdk"));
        assertThrows(
                IllegalArgumentException.class,
                () -> GatewaySystemProperties.requireUpstreamScheme(
                        "ftp", "ftp://a:21", "jdk"));
    }

    @Test
    void jdkAllowsHttps() {
        assertTrue(GatewaySystemProperties.allowsHttpsUpstream("jdk"));
        assertDoesNotThrow(() -> GatewaySystemProperties.requireUpstreamScheme(
                "https", "https://a:443", "jdk"));
    }
}
