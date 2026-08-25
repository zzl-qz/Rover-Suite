package com.rover.gateway.core.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.Test;

class GatewayRequestContextTest {

    @Test
    void markPhaseNoopsWhenTraceDisabled() {
        GatewayRequestContext context = new GatewayRequestContext(null, null, "/api/hello");
        context.setTraceEnabled(false);

        context.markPhase("receive", 1_000_000);

        assertEquals(0, context.phaseCostSumNanos());
        assertTrue(context.phaseCostsMillis().isEmpty());
    }

    @Test
    void markPhaseRecordsWhenTraceEnabled() {
        GatewayRequestContext context = new GatewayRequestContext(null, null, "/api/hello");

        context.markPhase("receive", 1_000_000);

        assertEquals(1_000_000, context.phaseCostSumNanos());
        assertEquals(1, context.phaseCostsMillis().size());
    }

    @Test
    void writeTextIsNoopAfterCompleted() {
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        GatewayRequestContext context = new GatewayRequestContext(
                channel.pipeline().firstContext(), null, "/api/hello");
        context.markCompleted();

        context.writeText(HttpResponseStatus.OK, "should-not-write");

        assertTrue(context.isCompleted());
        assertNull(channel.readOutbound());
        channel.finish();
    }
}
