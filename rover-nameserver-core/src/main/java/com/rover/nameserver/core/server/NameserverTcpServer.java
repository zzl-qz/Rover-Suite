package com.rover.nameserver.core.server;

import com.rover.nameserver.client.codec.RoverMessageDecoder;
import com.rover.nameserver.client.codec.RoverMessageEncoder;
import com.rover.nameserver.core.config.NameserverRuntimeConfigManager;
import com.rover.nameserver.core.consistency.DefaultWriteAckPolicy;
import com.rover.nameserver.core.consistency.WriteAckPolicy;
import com.rover.nameserver.core.health.HealthChecker;
import com.rover.nameserver.core.manage.NameserverHttpManageServer;
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
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:50:00
 * Description: Nameserver TCP 服务 + HTTP 管理口
 *
 * 这个类是什么：Nameserver 服务的总装配器与生命周期管理者。
 * 核心职责：①构造全部业务组件（注册表、订阅、推送、健康检查、分发器、运行时）；
 * ②组装并启动 Netty TCP 服务端（解码器 → 编码器 → 业务 Handler）；
 * ③启动健康检查与管理口，提供优雅关闭。
 * 被谁用：NameserverApplication 作为服务入口；NameserverRuntime 供管理口与配置热更新引用。
 */
@Slf4j
public class NameserverTcpServer {

    /** 业务线程数：至少 4，默认用 CPU 核数——业务 Handler 在此线程组执行，不占用 IO 线程 */
    private static final int BIZ_THREADS = Math.max(4, Runtime.getRuntime().availableProcessors());

    @Getter
    private final NameserverServerOptions options;
    /** 运行时整体（含各组件引用），暴露给管理口与配置热更新 */
    @Getter
    private final NameserverRuntime runtime;

    private final ServiceRegistry registry;
    private final SubscriptionManager subscriptionManager;
    private final PushService pushService;
    private final HealthChecker healthChecker;
    private final NameserverRequestDispatcher dispatcher;
    private final NameserverHttpManageServer manageServer;

    /** Netty 事件循环组与业务线程组、服务端 channel（关闭时使用） */
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private EventExecutorGroup bizGroup;
    private Channel serverChannel;

    /** 便捷构造：默认内存注册表 + 默认写确认策略 */
    public NameserverTcpServer(NameserverServerOptions options) {
        this(options, new InMemoryServiceRegistry(), new DefaultWriteAckPolicy());
    }

    /**
     * 完整构造：组装全部业务组件、配置管理，并构建运行时。
     *
     * @param options        服务端运行参数
     * @param registry       注入的注册表实现（便于测试替换）
     * @param writeAckPolicy 注入的写确认策略（便于测试/集群替换）
     */
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
                options.getHealthCheckIntervalMillis(),
                options.getInstanceExpireMillis());

        // 配置管理：先用 Options（YAML 来源）灌注初值，再叠加历史持久化的 overlay
        NameserverRuntimeConfigManager configManager = new NameserverRuntimeConfigManager();
        configManager.seed("nameserver.health.checkIntervalMillis",
                String.valueOf(options.getHealthCheckIntervalMillis()));
        configManager.seed("nameserver.heartbeat.timeoutMillis",
                String.valueOf(options.getHeartbeatTimeoutMillis()));
        configManager.seed("nameserver.instance.expireMillis",
                String.valueOf(options.getInstanceExpireMillis()));
        configManager.seed("nameserver.push.enabled", String.valueOf(options.isPushEnabled()));
        // YAML 之后叠 Admin 落盘的配置
        configManager.loadOverlayIfPresent();

        // 组装运行时：管理口与配置热更新都操作这一个对象
        this.runtime = new NameserverRuntime(options, registry, pushService, healthChecker, configManager);
        configManager.getApplier().bind(runtime);
        // runtime 绑定完成后重放全部配置，让 YAML/overlay 的值真正落到组件上
        configManager.reapplyAll();
        this.manageServer = new NameserverHttpManageServer(options.getManagePort(), runtime);
        this.dispatcher = new NameserverRequestDispatcher(
                registry, subscriptionManager, pushService, writeAckPolicy, options);
    }

    /**
     * 启动服务：初始化线程组 → 绑定 TCP 端口 → 启动健康检查与管理口。
     * 启动失败时先清理已创建资源再抛出异常。
     *
     * @throws IllegalStateException 绑定或初始化失败（含端口被占用）
     */
    public void start() {
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
                                    .addLast(bizGroup, new NameserverServerHandler(dispatcher));
                        }
                    });

            // 同步等待绑定完成，拿到服务端 channel 句柄供关闭使用
            serverChannel = bootstrap.bind(options.getPort()).sync().channel();
            healthChecker.start();
            manageServer.start();
            log.info("Rover Nameserver 已启动, port={}, managePort={}, writeAckMode={}, cluster={}",
                    options.getPort(),
                    options.getManagePort(),
                    options.getWriteAckMode(),
                    options.isClusterEnabled());
        } catch (Exception ex) {
            shutdown();
            throw new IllegalStateException("Nameserver 启动失败, port=" + options.getPort(), ex);
        }
    }

    /**
     * 优雅关闭：按依赖逆序关闭管理口 → 健康检查 → 服务端 channel → 各线程组，
     * 可安全重复调用（空引用已判空）。
     */
    public void shutdown() {
        manageServer.shutdown();
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