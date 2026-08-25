package com.rover.gateway.core.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.common.constants.HttpConstants;
import com.rover.gateway.core.filter.GatewayContextKeys;
import com.rover.gateway.core.filter.GatewayRequestContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

class GatewayHttpServerHandlerTraceTest {

    @Test
    void disabledDoesNotMintTraceId() {
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/hello");
        GatewayRequestContext context = new GatewayRequestContext(null, request, "/api/hello");

        GatewayHttpServerHandler.attachTraceId(request, context, false);

        assertFalse(request.headers().contains(HttpConstants.TRACE_ID_HEADER));
        assertNull(context.getAttribute(GatewayContextKeys.TRACE_ID));
    }

    @Test
    void disabledKeepsIncomingTraceId() {
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/hello");
        request.headers().set(HttpConstants.TRACE_ID_HEADER, "keep-me");
        GatewayRequestContext context = new GatewayRequestContext(null, request, "/api/hello");

        GatewayHttpServerHandler.attachTraceId(request, context, false);

        assertEquals("keep-me", request.headers().get(HttpConstants.TRACE_ID_HEADER));
        assertEquals("keep-me", context.getAttribute(GatewayContextKeys.TRACE_ID));
    }

    @Test
    void enabledMintsTraceIdWhenMissing() {
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/hello");
        GatewayRequestContext context = new GatewayRequestContext(null, request, "/api/hello");

        GatewayHttpServerHandler.attachTraceId(request, context, true);

        assertNotNull(request.headers().get(HttpConstants.TRACE_ID_HEADER));
        assertEquals(
                request.headers().get(HttpConstants.TRACE_ID_HEADER),
                context.getAttribute(GatewayContextKeys.TRACE_ID));
    }

    @Test
    void rawPathKeepsEscapesAndStripsQuery() {
        assertEquals("/api/a%20b", GatewayHttpServerHandler.rawPath("/api/a%20b?x=1"));
        assertEquals("/api/hello", GatewayHttpServerHandler.rawPath("/api/hello"));
        assertEquals("/", GatewayHttpServerHandler.rawPath(""));
    }

    @Test
    void occupyIsExclusiveUntilReleased() {
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        assertTrue(GatewayHttpServerHandler.tryOccupy(channel));
        assertFalse(GatewayHttpServerHandler.tryOccupy(channel));
        GatewayHttpServerHandler.releaseOccupy(channel);
        assertTrue(GatewayHttpServerHandler.tryOccupy(channel));
        channel.finish();
    }
}
