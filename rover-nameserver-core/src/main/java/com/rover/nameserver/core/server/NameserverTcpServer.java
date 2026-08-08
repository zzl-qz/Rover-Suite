package com.rover.nameserver.core.server;

import com.rover.nameserver.client.codec.RoverMessageDecoder;
import com.rover.nameserver.client.codec.RoverMessageEncoder;
import com.rover.nameserver.core.consistency.DefaultWriteAckPolicy;
import com.rover.nameserver.core.consistency.WriteAckPolicy;
import com.rover.nameserver.core.health.HealthChecker;
import com.rover.nameserver.core.push.PushService;
import com.rover.nameserver.core.push.SubscriptionManager;
import com.rover.nameserver.core.registry.InMemoryServiceRegistry;
import com.rover.nameserver.core.registry.ServiceRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:50:00
 * Description: Nameserver TCP 服务
 */
@Slf4j
public class NameserverTcpServer {

    private static final int BIZ_THREADS = Math.max(4, Runtime.getRuntime().availableProcessors());

    @Getter
    private final NameserverServerOptions options;

    private final ServiceRegistry registry;
    private final SubscriptionManager subscriptionManager;
    private final PushService pushService;
    private final HealthChecker healthChecker;
    private final NameserverRequestDispatcher dispatcher;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private EventExecutorGroup bizGroup;
    private Channel serverChannel;

    public NameserverTcpServer(NameserverServerOptions options) {
        this(options, new InMemoryServiceRegistry(), new DefaultWriteAckPolicy());
    }

    public NameserverTcpServer(
            NameserverServerOptions options, ServiceRegistry registry, WriteAckPolicy writeAckPolicy) {
        this.options = options;
        this.registry = registry;
        this.subscriptionManager = new SubscriptionManager();
        this.pushService = new PushService(subscriptionManager, options.isPushEnabled());
        this.healthChecker = new HealthChecker(
                registry,
                pushService,
                options.getHeartbeatTimeoutMillis(),
                options.getHealthCheckIntervalMillis());
        this.dispatcher = new NameserverRequestDispatcher(
                registry, subscriptionManager, pushService, writeAckPolicy, options);
    }

    public void start() {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        bizGroup = new DefaultEventExecutorGroup(BIZ_THREADS);

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(new RoverMessageDecoder())
                                    .addLast(new RoverMessageEncoder())
                                    // 注册表这类业务别堵在 IO 线程上
                                    .addLast(bizGroup, new NameserverServerHandler(dispatcher));
                        }
                    });

            serverChannel = bootstrap.bind(options.getPort()).sync().channel();
            healthChecker.start();
            log.info("Rover Nameserver 已启动, port={}, writeAckMode={}, cluster={}",
                    options.getPort(),
                    options.getWriteAckMode(),
                    options.isClusterEnabled());
        } catch (Exception ex) {
            shutdown();
            throw new IllegalStateException("Nameserver 启动失败, port=" + options.getPort(), ex);
        }
    }

    public void shutdown() {
        healthChecker.shutdown();
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bizGroup != null) {
            bizGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        log.info("Rover Nameserver 已关闭");
    }
}
