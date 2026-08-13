package com.rover.gateway.core.server;

import com.rover.gateway.core.config.GatewayRuntimeConfigManager;
import com.rover.gateway.core.discovery.DiscoverySettings;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.gateway.core.discovery.NameserverServiceDiscovery;
import com.rover.gateway.core.discovery.NoopServiceDiscovery;
import com.rover.common.spi.discovery.ServiceDiscovery;
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
 *
 * 这个类是什么：Gateway 进程的 Netty HTTP 服务端入口。
 * 核心职责：①按发现模式创建 ServiceDiscovery 和 GatewayRuntime；
 * ②启动 Netty 监听端口，IO 线程收包、业务线程池跑过滤器链；
 * ③shutdown 时优雅关闭线程池和发现客户端。
 * 被谁用：rover-gateway 启动模块创建并 start/shutdown。
 */
@Slf4j
public class GatewayHttpServer {

    /** 业务线程池大小，至少 4，随 CPU 核数放大。 */
    private static final int BIZ_THREADS = Math.max(4, Runtime.getRuntime().availableProcessors());

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

    /**
     * 最简构造：仅指定端口，其余用默认值。
     *
     * @param port 监听端口
     */
    public GatewayHttpServer(int port) {
        this(port, List.of(), 1024 * 1024, 3000, 30000, new FilterSettings(), defaultStaticDiscovery());
    }

    /**
     * 指定端口和初始路由表。
     *
     * @param port   监听端口
     * @param routes 初始路由列表
     */
    public GatewayHttpServer(int port, List<RouteConfig> routes) {
        this(port, routes, 1024 * 1024, 3000, 30000, new FilterSettings(), defaultStaticDiscovery());
    }

    /**
     * 指定端口、路由、body 上限和代理超时。
     *
     * @param port                   监听端口
     * @param routes                 初始路由列表
     * @param maxContentLengthBytes  单请求最大 body 字节
     * @param connectTimeoutMillis   代理连接超时
     * @param requestTimeoutMillis   代理请求超时
     * @param filterSettings         过滤器加载配置
     */
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

    /**
     * 全参数构造，组装 GatewayRuntime 并加载配置 overlay。
     *
     * @param port                   监听端口
     * @param routes                 初始路由列表
     * @param maxContentLengthBytes  单请求最大 body 字节
     * @param connectTimeoutMillis   代理连接超时
     * @param requestTimeoutMillis   代理请求超时
     * @param filterSettings         过滤器加载配置
     * @param discoverySettings      服务发现配置
     */
    public GatewayHttpServer(
            int port,
            List<RouteConfig> routes,
            int maxContentLengthBytes,
            int connectTimeoutMillis,
            int requestTimeoutMillis,
            FilterSettings filterSettings,
            DiscoverySettings discoverySettings) {
        this(port, routes, maxContentLengthBytes, connectTimeoutMillis, requestTimeoutMillis,
                filterSettings, discoverySettings, "round_robin");
    }

    /**
     * 全参数构造（含负载均衡策略名）。
     */
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
                ? "round_robin"
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
     *
     * @throws IllegalStateException 启动被中断或 Netty bind 失败
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
