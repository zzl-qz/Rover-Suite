package com.rover.gateway.core.server;

import com.rover.gateway.core.config.GatewayRuntimeConfigManager;
import com.rover.gateway.core.discovery.DiscoverySettings;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.gateway.core.discovery.NameserverServiceDiscovery;
import com.rover.gateway.core.discovery.NoopServiceDiscovery;
import com.rover.gateway.core.discovery.ServiceDiscovery;
import com.rover.gateway.core.filter.FilterSettings;
import com.rover.gateway.core.route.RouteConfig;
import com.rover.gateway.core.runtime.GatewayRuntime;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import java.util.List;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 14:22:00
 * Description: 启动 Gateway HTTP 服务，按发现模式组装过滤器链
 */
@Slf4j
public class GatewayHttpServer {

    private static final int BIZ_THREADS = Math.max(4, Runtime.getRuntime().availableProcessors());

    @Getter
    private final int port;
    private final int maxContentLengthBytes;
    private final ServiceDiscovery serviceDiscovery;
    @Getter
    private final GatewayRuntime runtime;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private EventExecutorGroup bizGroup;
    private Channel serverChannel;

    public GatewayHttpServer(int port) {
        this(port, List.of(), 1024 * 1024, 3000, 30000, new FilterSettings(), defaultStaticDiscovery());
    }

    public GatewayHttpServer(int port, List<RouteConfig> routes) {
        this(port, routes, 1024 * 1024, 3000, 30000, new FilterSettings(), defaultStaticDiscovery());
    }

    public GatewayHttpServer(
            int port,
            List<RouteConfig> routes,
            int maxContentLengthBytes,
            int connectTimeoutMillis,
            int requestTimeoutMillis,
            FilterSettings filterSettings) {
        this(port, routes, maxContentLengthBytes, connectTimeoutMillis, requestTimeoutMillis,
                filterSettings, defaultStaticDiscovery());
    }

    public GatewayHttpServer(
            int port,
            List<RouteConfig> routes,
            int maxContentLengthBytes,
            int connectTimeoutMillis,
            int requestTimeoutMillis,
            FilterSettings filterSettings,
            DiscoverySettings discoverySettings) {
        this.port = port;
        this.maxContentLengthBytes = maxContentLengthBytes;
        DiscoverySettings settings = discoverySettings == null ? defaultStaticDiscovery() : discoverySettings;
        this.serviceDiscovery = createServiceDiscovery(settings);

        GatewayRuntimeConfigManager configManager = new GatewayRuntimeConfigManager();
        configManager.seed("gateway.filter.enabled", String.valueOf(
                filterSettings == null || filterSettings.isEnabled()));
        configManager.seed("gateway.request.timeoutMillis", String.valueOf(requestTimeoutMillis));
        configManager.seed("gateway.loadbalance.strategy", "round_robin");
        // YAML 之后叠 Admin 落盘的配置
        configManager.loadOverlayIfPresent();

        this.runtime = new GatewayRuntime(
                port,
                routes,
                connectTimeoutMillis,
                requestTimeoutMillis,
                filterSettings,
                settings,
                serviceDiscovery,
                configManager);
        configManager.getApplier().bind(runtime);
        // 把 overlay/当前值真正打进运行时（超时、过滤器、LB）
        configManager.reapplyAll();
    }

    public void start() {
        serviceDiscovery.start();

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
                                    .addLast(new HttpServerCodec())
                                    .addLast(new HttpObjectAggregator(maxContentLengthBytes))
                                    .addLast(bizGroup, new GatewayHttpServerHandler(runtime));
                        }
                    });

            serverChannel = bootstrap.bind(port).sync().channel();
            log.info("Rover Gateway HTTP server listening on port {}, discovery={}, managePrefix=/_manage",
                    port, runtime.getDiscoveryType());
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            shutdown();
            throw new IllegalStateException("启动 Gateway HTTP 服务被中断", err);
        } catch (RuntimeException err) {
            shutdown();
            throw err;
        }
    }

    public void shutdown() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bizGroup != null) {
            bizGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        try {
            serviceDiscovery.close();
        } catch (Exception ex) {
            log.warn("关闭服务发现失败", ex);
        }
    }

    private static ServiceDiscovery createServiceDiscovery(DiscoverySettings settings) {
        if (settings.getType() == DiscoveryType.NAMESERVER) {
            return new NameserverServiceDiscovery(settings);
        }
        return new NoopServiceDiscovery();
    }

    private static DiscoverySettings defaultStaticDiscovery() {
        DiscoverySettings settings = new DiscoverySettings();
        settings.setType(DiscoveryType.STATIC);
        return settings;
    }
}
