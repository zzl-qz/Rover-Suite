package com.rover.gateway.core.server;

import com.rover.gateway.core.config.GatewayRuntimeConfigManager;
import com.rover.gateway.core.config.GatewayDefaults;
import com.rover.gateway.core.config.GatewayRuntimeConfigKeys;
import com.rover.gateway.core.config.GatewaySystemProperties;
import com.rover.common.constants.ManageApiPaths;
import com.rover.common.security.StrictSecurity;
import com.rover.common.security.TokenAuth;
import com.rover.gateway.core.discovery.DiscoverySettings;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.gateway.core.discovery.NameserverServiceDiscovery;
import com.rover.gateway.core.discovery.NoopServiceDiscovery;
import com.rover.common.spi.discovery.ServiceDiscovery;
import com.rover.common.spi.loadbalance.LoadBalancer;
import com.rover.gateway.core.filter.FilterSettings;
import com.rover.gateway.core.filter.ratelimit.RateLimitSettings;
import com.rover.gateway.core.proxy.HttpProxyClient;
import com.rover.gateway.core.route.RouteConfig;
import com.rover.gateway.core.runtime.GatewayRuntime;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;
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

    /** 业务线程池最小线程数。 */
    private static final int MIN_BIZ_THREADS = 4;

    /**
     * 业务线程池默认大小：等于 CPU 核数（下限 4），可用 -Drover.gateway.bizThreads 覆盖。
     * 异步转发模型下业务线程只做「收包 + 路由/发现/LB + 派发」，不阻塞等待上游，
     * 所以无需按倍数放大，少量线程即可驱动极高并发，弱机器也不浪费线程栈内存。
     */
    private static final int DEFAULT_BIZ_THREADS =
            Math.max(MIN_BIZ_THREADS, Runtime.getRuntime().availableProcessors());

    /** 业务线程池实际大小（系统属性可覆盖）。 */
    private static final int BIZ_THREADS =
            Integer.getInteger(GatewaySystemProperties.BIZ_THREADS, DEFAULT_BIZ_THREADS);

    /** 监听端口。 */
    @Getter
    private final int port;

    /** 监听地址，默认 0.0.0.0。 */
    private final String bindHost;

    /** 管理口鉴权 token（/_manage/**）；空表示不鉴权。 */
    private final String adminToken;

    /** 是否启用 Admin 管理面与运行时配置 overlay。 */
    private final boolean adminEnabled;

    /** 单请求最大 body 字节数，超过 TooLongFrameException。 */
    private final int maxContentLengthBytes;

    /** CORS 跨域配置，未启用时为默认关闭对象。 */
    private final CorsSettings corsSettings;

    /** true：业务 Handler 挂 EventLoop；false：挂 biz 池。有阻塞插件必须 false。 */
    private final boolean dispatchOnEventLoop;

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
        this(port, List.of(), GatewayDefaults.MAX_REQUEST_BODY_BYTES,
                HttpProxyClient.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                HttpProxyClient.DEFAULT_REQUEST_TIMEOUT_MILLIS,
                new FilterSettings(), defaultStaticDiscovery());
    }

    /** 指定端口和初始路由表构造。 */
    public GatewayHttpServer(int port, List<RouteConfig> routes) {
        this(port, routes, GatewayDefaults.MAX_REQUEST_BODY_BYTES,
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
        this(port, routes, maxContentLengthBytes, connectTimeoutMillis, requestTimeoutMillis,
                filterSettings, discoverySettings, loadBalanceStrategy, new CorsSettings());
    }

    /** 全参数构造（含负载均衡策略名和 CORS 配置）。 */
    public GatewayHttpServer(
            int port,
            List<RouteConfig> routes,
            int maxContentLengthBytes,
            int connectTimeoutMillis,
            int requestTimeoutMillis,
            FilterSettings filterSettings,
            DiscoverySettings discoverySettings,
            String loadBalanceStrategy,
            CorsSettings corsSettings) {
        this(port, routes, maxContentLengthBytes, connectTimeoutMillis, requestTimeoutMillis,
                filterSettings, discoverySettings, loadBalanceStrategy, corsSettings,
                GatewayDefaults.BIND_HOST, null, true);
    }

    /** 全参数构造（含绑定地址与管理口 token）。 */
    public GatewayHttpServer(
            int port,
            List<RouteConfig> routes,
            int maxContentLengthBytes,
            int connectTimeoutMillis,
            int requestTimeoutMillis,
            FilterSettings filterSettings,
            DiscoverySettings discoverySettings,
            String loadBalanceStrategy,
            CorsSettings corsSettings,
            String bindHost,
            String adminToken) {
        this(port, routes, maxContentLengthBytes, connectTimeoutMillis, requestTimeoutMillis,
                filterSettings, discoverySettings, loadBalanceStrategy, corsSettings,
                bindHost, adminToken, true,
                true, GatewayDefaults.METRICS_WINDOW_SECONDS,
                true, GatewayDefaults.TRACE_SLOW_THRESHOLD_MILLIS, GatewayDefaults.TRACE_SAMPLE_RATE,
                GatewayDispatch.onEventLoop());
    }

    /** 全参数构造（可显式关闭 Admin 管理面）。 */
    public GatewayHttpServer(
            int port,
            List<RouteConfig> routes,
            int maxContentLengthBytes,
            int connectTimeoutMillis,
            int requestTimeoutMillis,
            FilterSettings filterSettings,
            DiscoverySettings discoverySettings,
            String loadBalanceStrategy,
            CorsSettings corsSettings,
            String bindHost,
            String adminToken,
            boolean adminEnabled) {
        this(port, routes, maxContentLengthBytes, connectTimeoutMillis, requestTimeoutMillis,
                filterSettings, discoverySettings, loadBalanceStrategy, corsSettings,
                bindHost, adminToken, adminEnabled,
                true, GatewayDefaults.METRICS_WINDOW_SECONDS,
                true, GatewayDefaults.TRACE_SLOW_THRESHOLD_MILLIS, GatewayDefaults.TRACE_SAMPLE_RATE,
                GatewayDispatch.onEventLoop());
    }

    /** 全参数构造（含 YAML 观测开关与 EventLoop 分发）。 */
    public GatewayHttpServer(
            int port,
            List<RouteConfig> routes,
            int maxContentLengthBytes,
            int connectTimeoutMillis,
            int requestTimeoutMillis,
            FilterSettings filterSettings,
            DiscoverySettings discoverySettings,
            String loadBalanceStrategy,
            CorsSettings corsSettings,
            String bindHost,
            String adminToken,
            boolean adminEnabled,
            boolean metricsEnabled,
            int metricsWindowSeconds,
            boolean traceEnabled,
            long traceSlowThresholdMillis,
            double traceSampleRate,
            boolean dispatchOnEventLoop) {
        this.port = port;
        this.bindHost = bindHost == null || bindHost.isBlank() ? GatewayDefaults.BIND_HOST : bindHost.trim();
        this.adminToken = adminToken;
        this.adminEnabled = adminEnabled;
        this.maxContentLengthBytes = maxContentLengthBytes;
        this.corsSettings = corsSettings == null ? new CorsSettings() : corsSettings;
        this.dispatchOnEventLoop = dispatchOnEventLoop;
        DiscoverySettings settings = discoverySettings == null ? defaultStaticDiscovery() : discoverySettings;
        this.serviceDiscovery = createServiceDiscovery(settings);

        String lbStrategy = loadBalanceStrategy == null || loadBalanceStrategy.isBlank()
                ? LoadBalancer.ROUND_ROBIN
                : loadBalanceStrategy.trim();
        GatewayRuntimeConfigManager configManager = new GatewayRuntimeConfigManager();
        configManager.seed(GatewayRuntimeConfigKeys.FILTER_ENABLED, String.valueOf(
                filterSettings == null || filterSettings.isEnabled()));
        configManager.seed(GatewayRuntimeConfigKeys.REQUEST_TIMEOUT_MILLIS, String.valueOf(requestTimeoutMillis));
        configManager.seed(GatewayRuntimeConfigKeys.LOAD_BALANCE_STRATEGY, lbStrategy);
        RateLimitSettings rateLimit = filterSettings == null ? new RateLimitSettings() : filterSettings.getRateLimit();
        configManager.seed(GatewayRuntimeConfigKeys.RATE_LIMIT_ENABLED, String.valueOf(rateLimit.isEnabled()));
        configManager.seed(GatewayRuntimeConfigKeys.RATE_LIMIT_ALGORITHM, rateLimit.getAlgorithm());
        configManager.seed(GatewayRuntimeConfigKeys.RATE_LIMIT_KEY, rateLimit.getKey());
        configManager.seed(GatewayRuntimeConfigKeys.RATE_LIMIT_PERMITS_PER_SECOND,
                String.valueOf(rateLimit.getPermitsPerSecond()));
        configManager.seed(GatewayRuntimeConfigKeys.RATE_LIMIT_BURST, String.valueOf(rateLimit.getBurst()));
        configManager.seed(GatewayRuntimeConfigKeys.RATE_LIMIT_LIMIT, String.valueOf(rateLimit.getLimit()));
        configManager.seed(GatewayRuntimeConfigKeys.RATE_LIMIT_WINDOW_SECONDS,
                String.valueOf(rateLimit.getWindowSeconds()));
        configManager.seed(GatewayRuntimeConfigKeys.METRICS_ENABLED, String.valueOf(metricsEnabled));
        configManager.seed(GatewayRuntimeConfigKeys.METRICS_WINDOW_SECONDS, String.valueOf(metricsWindowSeconds));
        configManager.seed(GatewayRuntimeConfigKeys.TRACE_ENABLED, String.valueOf(traceEnabled));
        configManager.seed(GatewayRuntimeConfigKeys.TRACE_SLOW_THRESHOLD_MILLIS,
                String.valueOf(traceSlowThresholdMillis));
        configManager.seed(GatewayRuntimeConfigKeys.TRACE_SAMPLE_RATE, String.valueOf(traceSampleRate));
        this.runtime = new GatewayRuntime(
                port,
                routes,
                connectTimeoutMillis,
                requestTimeoutMillis,
                filterSettings,
                settings,
                serviceDiscovery,
                configManager,
                adminToken);
        // 插件在 Runtime 构造期间注册自己的配置，随后再读取 overlay 才能恢复插件已保存的值。
        if (adminEnabled) {
            configManager.loadOverlayIfPresent();
        } else {
            log.info("Admin 管理面已关闭，跳过 Gateway 运行时配置覆盖文件");
        }
        configManager.getApplier().bind(runtime);
        // 把 overlay/当前值真正打进运行时（超时、过滤器、LB 与已装配插件）。
        configManager.reapplyAll();
    }

    /**
     * 启动服务发现、Netty 服务端并开始监听。
     */
    public void start() {
        serviceDiscovery.start();

        IoTransport io = IoTransport.current();
        bossGroup = io.newGroup(1);
        workerGroup = io.newGroup(0);
        if (!dispatchOnEventLoop) {
            bizGroup = new DefaultEventExecutorGroup(BIZ_THREADS);
        }

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(io.serverChannelClass())
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            var pipeline = ch.pipeline()
                                    .addLast(new HttpServerCodec())
                                    .addLast(new HttpServerKeepAliveHandler())
                                    .addLast(new CorsHandler(corsSettings));
                            GatewayHttpServerHandler handler = new GatewayHttpServerHandler(
                                    runtime, adminEnabled, maxContentLengthBytes);
                            if (dispatchOnEventLoop) {
                                // 轻路径：别再 hop 到 biz 池。插件禁止阻塞 EventLoop。
                                pipeline.addLast(handler);
                            } else {
                                pipeline.addLast(bizGroup, handler);
                            }
                        }
                    });

            serverChannel = bootstrap.bind(bindHost, port).sync().channel();
            warnIfAdminTokenBlank();
            log.info("Rover Gateway HTTP server listening on {}:{}, ioTransport={}, discovery={}, adminEnabled={}, dispatchOnEventLoop={}, managePrefix={}",
                    bindHost, port, io.name().toLowerCase(), runtime.getDiscoveryType(), adminEnabled,
                    dispatchOnEventLoop, adminEnabled ? ManageApiPaths.PREFIX : "disabled");
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            shutdown();
            throw new IllegalStateException("启动 Gateway HTTP 服务被中断", err);
        } catch (RuntimeException err) {
            shutdown();
            throw err;
        }
    }

    /**
     * 管理面开启且 token 为空：默认 WARN；严格安全模式直接启动失败。
     * 开启：环境变量 ROVER_STRICT_SECURITY=true 或 -Drover.strictSecurity=true
     */
    private void warnIfAdminTokenBlank() {
        if (!adminEnabled || !TokenAuth.isBlank(adminToken)) {
            return;
        }
        String message = "管理口 adminToken 为空，/_manage/** 不鉴权；当前 bindHost=" + bindHost
                + "。若对公网或局域网暴露，等同可改路由/配置。生产请设置 adminToken，或收紧 bindHost。"
                + "正式环境可设 ROVER_STRICT_SECURITY=true 强制拒绝空 token 启动。";
        if (StrictSecurity.enabled()) {
            throw new IllegalStateException(message);
        }
        log.warn(message);
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
        runtime.close();
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
