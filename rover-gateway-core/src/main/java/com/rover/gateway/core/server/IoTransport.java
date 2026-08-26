package com.rover.gateway.core.server;

import com.rover.gateway.core.config.GatewaySystemProperties;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.kqueue.KQueueSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;

/**
 * 入站/出站同一套 I/O。Linux=Epoll，macOS=KQueue，没有原生库退 NIO。
 * EventLoop 和 Channel 必须一家。
 */
@Slf4j
public enum IoTransport {
    EPOLL,
    KQUEUE,
    NIO;

    private static volatile IoTransport cached;

    public static IoTransport current() {
        IoTransport t = cached;
        if (t == null) {
            cached = t = detect();
        }
        return t;
    }

    static IoTransport detect() {
        String raw = System.getProperty(GatewaySystemProperties.IO_TRANSPORT, "auto");
        String mode = raw.isBlank() ? "auto" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "nio" -> NIO;
            case "epoll" -> Epoll.isAvailable() ? EPOLL : unavailable("epoll");
            case "kqueue" -> KQueue.isAvailable() ? KQUEUE : unavailable("kqueue");
            default -> Epoll.isAvailable() ? EPOLL : KQueue.isAvailable() ? KQUEUE : NIO;
        };
    }

    /** nThreads=0 表示 Netty 默认线程数。 */
    public EventLoopGroup newGroup(int nThreads) {
        return switch (this) {
            case EPOLL -> new EpollEventLoopGroup(nThreads);
            case KQUEUE -> new KQueueEventLoopGroup(nThreads);
            case NIO -> new NioEventLoopGroup(nThreads);
        };
    }

    public Class<? extends ServerChannel> serverChannelClass() {
        return switch (this) {
            case EPOLL -> EpollServerSocketChannel.class;
            case KQUEUE -> KQueueServerSocketChannel.class;
            case NIO -> NioServerSocketChannel.class;
        };
    }

    public Class<? extends Channel> clientChannelClass() {
        return switch (this) {
            case EPOLL -> EpollSocketChannel.class;
            case KQUEUE -> KQueueSocketChannel.class;
            case NIO -> NioSocketChannel.class;
        };
    }

    public boolean sameFamily(EventLoop loop) {
        EventLoopGroup parent = loop.parent();
        return switch (this) {
            case EPOLL -> parent instanceof EpollEventLoopGroup;
            case KQUEUE -> parent instanceof KQueueEventLoopGroup;
            case NIO -> parent instanceof NioEventLoopGroup;
        };
    }

    private static IoTransport unavailable(String wanted) {
        log.warn("ioTransport={} 当前不可用，退回 nio", wanted);
        return NIO;
    }
}
