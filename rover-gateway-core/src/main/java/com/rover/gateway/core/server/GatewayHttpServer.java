package com.rover.gateway.core.server;

import com.rover.common.spi.Filter;
import com.rover.gateway.core.filter.FilterSettings;
import com.rover.gateway.core.filter.GatewayFilterAssembler;
import com.rover.gateway.core.proxy.HttpProxyClient;
import com.rover.gateway.core.route.RouteConfig;
import com.rover.gateway.core.route.RouteMatcher;
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
 * Description: 启动 Gateway HTTP 服务，组装过滤器链并监听外部请求
 */
@Slf4j
public class GatewayHttpServer {

    /** 业务线程数：至少 4，默认跟 CPU 核数对齐。 */
    private static final int BIZ_THREADS = Math.max(4, Runtime.getRuntime().availableProcessors());

    @Getter
    private final int port;
    /** 启动时加载好的静态路由。 */
    private final List<RouteConfig> routes;
    /** 允许的最大请求体字节数，超过会返回 413。 */
    private final int maxContentLengthBytes;
    private final int connectTimeoutMillis;
    private final int requestTimeoutMillis;
    /** 过滤器加载配置，例如 plugins 目录。 */
    private final FilterSettings filterSettings;
    /** 启动时组装好的过滤器链，后续每个请求复用这份列表。 */
    private final List<Filter> filters;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private EventExecutorGroup bizGroup;
    private Channel serverChannel;

    public GatewayHttpServer(int port) {
        this(port, List.of(), 1024 * 1024, 3000, 30000, new FilterSettings());
    }

    public GatewayHttpServer(int port, List<RouteConfig> routes) {
        this(port, routes, 1024 * 1024, 3000, 30000, new FilterSettings());
    }

    public GatewayHttpServer(
            int port,
            List<RouteConfig> routes,
            int maxContentLengthBytes,
            int connectTimeoutMillis,
            int requestTimeoutMillis,
            FilterSettings filterSettings) {
        this.port = port;
        this.routes = routes;
        this.maxContentLengthBytes = maxContentLengthBytes;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.requestTimeoutMillis = requestTimeoutMillis;
        this.filterSettings = filterSettings == null ? new FilterSettings() : filterSettings;
        // 启动阶段就把内置过滤器 + plugins 外挂过滤器组装好。
        this.filters = new GatewayFilterAssembler().assemble(
                this.filterSettings,
                new RouteMatcher(routes),
                new HttpProxyClient(connectTimeoutMillis, requestTimeoutMillis));
    }

    /**
     * 启动 Gateway HTTP 服务，负责监听外部 HTTP 请求。
     */
    public void start() {
        // boss 只负责接收连接，worker 负责网络读写。
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        // 业务线程池承载路由、过滤器和代理转发编排，避免阻塞 Netty IO 线程。
        bizGroup = new DefaultEventExecutorGroup(BIZ_THREADS);

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    // HTTP 编解码器负责把字节流转换成 HTTP 请求/响应对象。
                                    .addLast(new HttpServerCodec())
                                    // 聚合器负责把分段 HTTP 消息聚合成 FullHttpRequest。
                                    .addLast(new HttpObjectAggregator(maxContentLengthBytes))
                                    // 业务处理器跑在 bizGroup，内部会启动过滤器链。
                                    .addLast(bizGroup, new GatewayHttpServerHandler(filters));
                        }
                    });

            serverChannel = bootstrap.bind(port).sync().channel();
            log.info("Rover Gateway HTTP server listening on port {}", port);
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
     * 关闭 Gateway HTTP 服务，释放 Netty 线程组和监听端口。
     */
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
    }
}
