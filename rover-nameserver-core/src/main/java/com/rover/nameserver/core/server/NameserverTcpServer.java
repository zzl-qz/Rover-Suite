package com.rover.nameserver.core.server;

import com.rover.common.codec.RoverMessageDecoder;
import com.rover.common.codec.RoverMessageEncoder;
import com.rover.nameserver.core.cluster.NameserverGeneration;
import com.rover.nameserver.core.config.NameserverRuntimeConfigManager;
import com.rover.nameserver.core.consistency.DefaultWriteAckPolicy;
import com.rover.nameserver.core.consistency.WriteAckPolicy;
import com.rover.nameserver.core.event.EventBusBootstrap;
import com.rover.nameserver.core.event.support.NameserverServices;
import com.rover.nameserver.core.health.HealthChecker;
import com.rover.nameserver.core.manage.NameserverHttpManageServer;
import com.rover.nameserver.core.metrics.NameserverMetricsRegistry;
import com.rover.nameserver.core.push.PushService;
import com.rover.nameserver.core.push.SubscriptionManager;
import com.rover.nameserver.core.registry.InMemoryServiceRegistry;
import com.rover.nameserver.core.registry.ServiceRegistry;
import com.rover.nameserver.core.runtime.NameserverRuntime;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:50:00
 * Description: Nameserver 服务总装配器与生命周期管理者：组装业务组件、启动 Netty TCP 服务端、健康检查与管理口，提供优雅关闭
 */
@Slf4j
public class NameserverTcpServer {

    /** 业务线程池最小线程数，再按 CPU 核数放大——业务 Handler 在此线程组执行，不占用 IO 线程 */
    private static final int MIN_BIZ_THREADS = 4;

    /** 业务线程数：随 CPU 核数放大 */
    private static final int BIZ_THREADS = Math.max(MIN_BIZ_THREADS, Runtime.getRuntime().availableProcessors());

    @Getter
    private final NameserverServerOptions options;
    /** 运行时整体（含各组件引用），暴露给管理口与配置热更新 */
    @Getter
    private final NameserverRuntime runtime;

    private final ServiceRegistry registry;
    private final SubscriptionManager subscriptionManager;
    private final PushService pushService;
    private final HealthChecker healthChecker;
    private final EventBusBootstrap eventBus;
    private final NameserverRequestDispatcher dispatcher;
    private final NameserverHttpManageServer manageServer;
    /** 指标注册表：pipeline 中统计 TCP 连接数 */
    private final NameserverMetricsRegistry metrics;

    /** Netty 事件循环组与业务线程组、服务端 channel（关闭时使用） */
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private EventExecutorGroup bizGroup;
    private Channel serverChannel;
    /** 生命周期保护：防止重复 start 创建第二套线程组。 */
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** 便捷构造：默认内存注册表 + 默认写确认策略 */
    public NameserverTcpServer(NameserverServerOptions options) {
        this(options, new InMemoryServiceRegistry(), new DefaultWriteAckPolicy());
    }

    /** 完整构造：组装全部业务组件、配置管理并构建运行时。 */
    public NameserverTcpServer(
            NameserverServerOptions options, ServiceRegistry registry, WriteAckPolicy writeAckPolicy) {
        this.options = options;
        this.registry = registry;
        this.subscriptionManager = new SubscriptionManager();
        // 指标注册表：生命周期计数 + 最近事件 + TCP 连接数，注入各业务组件
        NameserverMetricsRegistry metrics = new NameserverMetricsRegistry();
        this.metrics = metrics;
        // 世代可替换：单机 ProcessLocal；集群以后注入集群权威 Generation
        NameserverGeneration generation = NameserverGeneration.processLocal();
        this.pushService = new PushService(subscriptionManager, options.isPushEnabled(), generation, metrics);
        this.healthChecker = new HealthChecker(
                registry,
                pushService,
                options.getHeartbeatTimeoutMillis(),
                options.getHealthCheckIntervalMillis(),
                options.getInstanceExpireMillis(),
                metrics);

        // 事件总线：显式注册协议 Listener（Handler 只做 TCP→Event）
        NameserverServices services = new NameserverServices(
                registry, subscriptionManager, pushService, writeAckPolicy, options, generation, metrics);
        this.eventBus = new EventBusBootstrap("rover-nameserver");
        this.eventBus.start(services);
        this.dispatcher = new NameserverRequestDispatcher(eventBus.getEventBus(), services);

        // 配置管理：先用 Options（YAML 来源）灌注初值，再叠加历史持久化的 overlay
        NameserverRuntimeConfigManager configManager = new NameserverRuntimeConfigManager();
        configManager.seed("nameserver.health.checkIntervalMillis",
                String.valueOf(options.getHealthCheckIntervalMillis()));
        configManager.seed("nameserver.heartbeat.timeoutMillis",
                String.valueOf(options.getHeartbeatTimeoutMillis()));
        configManager.seed("nameserver.instance.expireMillis",
                String.valueOf(options.getInstanceExpireMillis()));
        configManager.seed("nameserver.push.enabled", String.valueOf(options.isPushEnabled()));
        configManager.loadOverlayIfPresent();

        this.runtime = new NameserverRuntime(options, registry, pushService, healthChecker, configManager, metrics);
        configManager.getApplier().bind(runtime);
        configManager.reapplyAll();
        this.manageServer = new NameserverHttpManageServer(
                options.getManagePort(), options.getManageBindHost(), runtime);
    }

    /**
     * 启动服务：初始化线程组 → 绑定 TCP 端口 → 启动健康检查与管理口。
     * 启动失败时先清理已创建资源再抛出异常。
     *
     * @throws IllegalStateException 绑定或初始化失败（含端口被占用）
     */
    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("Nameserver 已关闭，不能重新启动");
        }
        if (!started.compareAndSet(false, true)) {
            log.warn("Nameserver 已启动，忽略重复 start");
            return;
        }
        // boss 单线程接受连接；worker 处理 IO 读写；biz 独立线程组跑业务，避免阻塞 IO 线程
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
                            // pipeline 组装：解码器（字节→RoverMessage）→ 编码器（响应写出）
                            // → 业务 Handler（挂在 bizGroup，业务逻辑不占 IO 线程）
                            ch.pipeline()
                                    .addLast(new RoverMessageDecoder())
                                    .addLast(new RoverMessageEncoder())
                                    .addLast(bizGroup, new NameserverServerHandler(dispatcher, metrics));
                        }
                    });

            // 同步等待绑定完成，拿到服务端 channel 句柄供关闭使用
            serverChannel = bootstrap.bind(options.getBindHost(), options.getPort()).sync().channel();
            healthChecker.start();
            manageServer.start();
            log.info("Rover Nameserver 已启动, bind={}:{}, manageBind={}:{}, writeAckMode={}, cluster={}, epoch={}",
                    options.getBindHost(),
                    options.getPort(),
                    options.getManageBindHost(),
                    options.getManagePort(),
                    options.getWriteAckMode(),
                    options.isClusterEnabled(),
                    pushService.getEpoch());
        } catch (Exception ex) {
            shutdown();
            throw new IllegalStateException("Nameserver 启动失败, port=" + options.getPort(), ex);
        }
    }

    /** 优雅关闭：按依赖逆序关闭管理口 → 健康检查 → 服务端 channel → 各线程组，可安全重复调用。 */
    public void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        started.set(false);
        manageServer.shutdown();
        healthChecker.shutdown();
        eventBus.shutdown();
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
