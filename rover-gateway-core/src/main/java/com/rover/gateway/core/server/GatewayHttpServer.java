package com.rover.gateway.core.server;

import com.rover.gateway.core.config.GatewayRuntimeConfigManager;
import com.rover.gateway.core.discovery.DiscoverySettings;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.gateway.core.discovery.NameserverServiceDiscovery;
import com.rover.gateway.core.discovery.NoopServiceDiscovery;
import com.rover.common.constants.ProtocolConstants;
import com.rover.common.spi.discovery.ServiceDiscovery;
import com.rover.common.spi.loadbalance.LoadBalancer;
import com.rover.gateway.core.filter.FilterSettings;
import com.rover.gateway.core.proxy.HttpProxyClient;
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

    /** 业务线程池最小线程数，再按 CPU 核数放大。 */
    private static final int MIN_BIZ_THREADS = 4;

    /** 业务线程池大小，随 CPU 核数放大。 */
    private static final int BIZ_THREADS = Math.max(MIN_BIZ_THREADS, Runtime.getRuntime().availableProcessors());

    /** 监听端口。 */
    @Getter
    private final int port;

    /** 单请求最大 body 字节数，超过 TooLongFrameException。 */
    private final int maxContentLengthBytes;

    /** 服务发现客户端，STATIC 时为 NoopServiceDiscovery。 */
    private final ServiceDiscovery serviceDiscovery;

    /** 网关运行时可变状态，含路由、过滤器链、配置管理。 */
    @Getter
    private final GatewayRuntime runtime;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private EventExecutorGroup bizGroup;
    private Channel serverChannel;

    /** 最简构造：仅指定端口，其余用默认值。 */
    public GatewayHttpServer(int port) {
        this(port, List.of(), ProtocolConstants.MAX_BODY_LENGTH,
                HttpProxyClient.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                HttpProxyClient.DEFAULT_REQUEST_TIMEOUT_MILLIS,
                new FilterSettings(), defaultStaticDiscovery());
    }

    /** 指定端口和初始路由表构造。 */
    public GatewayHttpServer(int port, List<RouteConfig> routes) {
        this(port, routes, ProtocolConstants.MAX_BODY_LENGTH,
                HttpProxyClient.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                HttpProxyClient.DEFAULT_REQUEST_TIMEOUT_MILLIS,
                new FilterSettings(), defaultStaticDiscovery());
    }

    /** 指定端口、路由、body 上限和代理超时。 */
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

    /** 全参数构造，组装 GatewayRuntime 并加载配置 overlay。 */
    public GatewayHttpServer(
            int port,
            List<RouteConfig> routes,
            int maxContentLengthBytes,
            int connectTimeoutMillis,
            int requestTimeoutMillis,
            FilterSettings filterSettings,
            DiscoverySettings discoverySettings) {
        this(port, routes, maxContentLengthBytes, connectTimeoutMillis, requestTimeoutMillis,
                filterSettings, discoverySettings, LoadBalancer.ROUND_ROBIN);
    }

    /** 全参数构造（含负载均衡策略名）。 */
    public GatewayHttpServer(
            int port,
            List<RouteConfig> routes,
            int maxContentLengthBytes,
            int connectTimeoutMillis,
            int requestTimeoutMillis,
            FilterSettings filterSettings,
            DiscoverySettings discoverySettings,
            String loadBalanceStrategy) {
        this.port = port;
        this.maxContentLengthBytes = maxContentLengthBytes;
        DiscoverySettings settings = discoverySettings == null ? defaultStaticDiscovery() : discoverySettings;
        this.serviceDiscovery = createServiceDiscovery(settings);

        String lbStrategy = loadBalanceStrategy == null || loadBalanceStrategy.isBlank()
                ? LoadBalancer.ROUND_ROBIN
                : loadBalanceStrategy.trim();
        GatewayRuntimeConfigManager configManager = new GatewayRuntimeConfigManager();
        configManager.seed("gateway.filter.enabled", String.valueOf(
                filterSettings == null || filterSettings.isEnabled()));
        configManager.seed("gateway.request.timeoutMillis", String.valueOf(requestTimeoutMillis));
        configManager.seed("gateway.loadbalance.strategy", lbStrategy);
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

    /**
     * 启动服务发现、Netty 服务端并开始监听。
     */
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

    /** 关闭 Netty 通道、线程池和服务发现客户端。 */
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

    /** 按 discovery.type 创建对应的 ServiceDiscovery 实现。 */
    private static ServiceDiscovery createServiceDiscovery(DiscoverySettings settings) {
        if (settings.getType() == DiscoveryType.NAMESERVER) {
            return new NameserverServiceDiscovery(settings);
        }
        return new NoopServiceDiscovery();
    }

    /** 返回 type=STATIC 的默认发现配置。 */
    private static DiscoverySettings defaultStaticDiscovery() {
        DiscoverySettings settings = new DiscoverySettings();
        settings.setType(DiscoveryType.STATIC);
        return settings;
    }
}
