package com.rover.agent.core.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DiscoveryModeTest {

    @Test
    void parsesGatewayReportedModes() {
        assertEquals(DiscoveryMode.NAMESERVER, DiscoveryMode.from("NAMESERVER"));
        assertEquals(DiscoveryMode.NAMESERVER, DiscoveryMode.from(" nameserver "));
        assertEquals(DiscoveryMode.NACOS, DiscoveryMode.from("nacos"));
        assertEquals(DiscoveryMode.STATIC, DiscoveryMode.from("STATIC"));
    }

    @Test
    void blankValuesFallBackToStaticAndUnrecognizedValuesToUnknown() {
        assertEquals(DiscoveryMode.STATIC, DiscoveryMode.from(null));
        assertEquals(DiscoveryMode.STATIC, DiscoveryMode.from("  "));
        assertEquals(DiscoveryMode.UNKNOWN, DiscoveryMode.from("ETCD"));
    }
}