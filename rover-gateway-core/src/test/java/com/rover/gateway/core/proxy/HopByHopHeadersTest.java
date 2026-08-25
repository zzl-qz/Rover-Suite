package com.rover.gateway.core.proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.util.AsciiString;
import org.junit.jupiter.api.Test;

class HopByHopHeadersTest {

    @Test
    void matchesHopByHopWithoutCaringAboutCase() {
        assertTrue(HopByHopHeaders.isHopByHop("Connection"));
        assertTrue(HopByHopHeaders.isHopByHop("TRANSFER-ENCODING"));
        assertTrue(HopByHopHeaders.isHopByHop(AsciiString.cached("keep-alive")));
        assertTrue(HopByHopHeaders.isHopByHop("Accept-Encoding"));
        assertFalse(HopByHopHeaders.isHopByHop("X-Custom"));
        assertFalse(HopByHopHeaders.isHopByHop(null));
    }

    @Test
    void matchesManagedForwardHeaders() {
        assertTrue(HopByHopHeaders.isManagedForward("X-Request-Id"));
        assertTrue(HopByHopHeaders.isManagedForward("x-forwarded-for"));
        assertFalse(HopByHopHeaders.isManagedForward("Authorization"));
    }
}
