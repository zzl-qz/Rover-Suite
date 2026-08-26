package com.rover.gateway.core.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.gateway.core.config.GatewaySystemProperties;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.jupiter.api.Test;

class IoTransportTest {

    @Test
    void forceNioIgnoresNative() {
        String previous = System.getProperty(GatewaySystemProperties.IO_TRANSPORT);
        try {
            System.setProperty(GatewaySystemProperties.IO_TRANSPORT, "nio");
            assertEquals(IoTransport.NIO, IoTransport.detect());
        } finally {
            if (previous == null) {
                System.clearProperty(GatewaySystemProperties.IO_TRANSPORT);
            } else {
                System.setProperty(GatewaySystemProperties.IO_TRANSPORT, previous);
            }
        }
    }

    @Test
    void eachKindHasMatchingChannelClasses() {
        for (IoTransport transport : IoTransport.values()) {
            assertNotNull(transport.serverChannelClass());
            assertNotNull(transport.clientChannelClass());
        }
    }

    @Test
    void nioLoopOnlyMatchesNioFamily() {
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        try {
            assertTrue(IoTransport.NIO.sameFamily(group.next()));
            assertFalse(IoTransport.EPOLL.sameFamily(group.next()));
            assertFalse(IoTransport.KQUEUE.sameFamily(group.next()));
        } finally {
            group.shutdownGracefully();
        }
    }
}
