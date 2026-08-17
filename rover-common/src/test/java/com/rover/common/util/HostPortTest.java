package com.rover.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class HostPortTest {

    @Test
    void parsesBracketedIpv6() {
        HostPort parsed = HostPort.require("[2001:db8::1]:8888", "address");
        assertEquals("2001:db8::1", parsed.host());
        assertEquals(8888, parsed.port());
    }

    @Test
    void rejectsAmbiguousBareIpv6() {
        assertThrows(IllegalArgumentException.class,
                () -> HostPort.require("2001:db8::1:8888", "address"));
    }
}
