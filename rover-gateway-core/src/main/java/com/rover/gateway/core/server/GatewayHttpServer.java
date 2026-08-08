/**
 * 作者：Daylight
 * 创建时间：2026-08-08 14:22:00
 * 描述：负责启动和关闭 Gateway 对外 HTTP 服务
 */
package com.rover.gateway.core.server;

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
 * 接收外部Http请求
 */
@Slf4j
public class GatewayHttpServer {

    private static final int BIZ_THREADS = Math.max(4, Runtime.getRuntime().availableProcessors());

    @Getter
    private final int port;
    private final List<RouteConfig> routes;
    private final int maxContentLengthBytes;
    private final int connectTimeoutMillis;
    private final int requestTimeoutMillis;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private EventExecutorGroup bizGroup;
    private Channel serverChannel;

    public GatewayHttpServer(int port) {
        this(port, List.of(), 1024 * 1024, 3000, 30000);
    }

    public GatewayHttpServer(int port, List<RouteConfig> routes) {
        this(port, routes, 1024 * 1024, 3000, 30000);
    }

    public GatewayHttpServer(
            int port,
            List<RouteConfig> routes,
            int maxContentLengthBytes,
            int connectTimeoutMillis,
            int requestTimeoutMillis) {
        this.port = port;
        this.routes = routes;
        this.maxContentLengthBytes = maxContentLengthBytes;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.requestTimeoutMillis = requestTimeoutMillis;
    }

    /**
     * 启动 Gateway HTTP 服务，负责监听外部 HTTP 请求。
     */
    public void start() {
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
                                    // http请求处理器
                                    .addLast(bizGroup, newGatewayHandler());
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

    private GatewayHttpServerHandler newGatewayHandler() {
        return new GatewayHttpServerHandler(
                new RouteMatcher(routes),
                new HttpProxyClient(connectTimeoutMillis, requestTimeoutMillis));
    }
}
