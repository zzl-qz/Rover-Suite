package com.rover.nameserver.core.manage;

import com.rover.nameserver.core.runtime.NameserverRuntime;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:45:00
 * Description: 基于 Netty 的轻量 HTTP 管理口，与 TCP 注册口并行监听，将请求转交 NameserverManageApi 并管理启停生命周期
 */
@Slf4j
public class NameserverHttpManageServer {

    /** 管理 API 可能执行配置落盘，必须与 Netty IO EventLoop 隔离。 */
    private static final int MANAGE_BIZ_THREADS = 2;

    /** HTTP 请求体大小上限（字节），注册/心跳请求都很小，1MB 足够。 */
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    /** HTTP 管理口监听端口，0 或未配置表示不启用 */
    private final int port;
    /** HTTP 管理口监听地址 */
    private final String bindHost;
    /** 管理 API 处理器，负责路由与 JSON 响应 */
    private final NameserverManageApi manageApi;

    /** Netty boss 线程组，负责 accept */
    private EventLoopGroup bossGroup;
    /** Netty worker 线程组，负责 IO */
    private EventLoopGroup workerGroup;
    /** 管理 API 业务线程组，承载阻塞文件 IO。 */
    private EventExecutorGroup bizGroup;
    /** 服务端 channel，关闭时使用 */
    private Channel serverChannel;

    public NameserverHttpManageServer(int port, String bindHost, NameserverRuntime runtime) {
        this.port = port;
        this.bindHost = bindHost == null || bindHost.isBlank() ? "0.0.0.0" : bindHost.trim();
        this.manageApi = new NameserverManageApi(runtime);
    }

    /** 启动 HTTP 管理口；port<=0 时只打日志并返回。 */
    public void start() {
        if (port <= 0) {
            log.info("Nameserver managePort 未启用");
            return;
        }
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(1);
        bizGroup = new DefaultEventExecutorGroup(MANAGE_BIZ_THREADS);
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(new HttpServerCodec())
                                    .addLast(new HttpObjectAggregator(MAX_BODY_BYTES))
                                    .addLast(bizGroup, new SimpleChannelInboundHandler<FullHttpRequest>() {
                                        @Override
                                        protected void channelRead0(
                                                io.netty.channel.ChannelHandlerContext ctx,
                                                FullHttpRequest request) {
                                            String path = new QueryStringDecoder(request.uri()).path();
                                            manageApi.handle(ctx, request, path);
                                        }
                                    });
                        }
                    });
            serverChannel = bootstrap.bind(bindHost, port).sync().channel();
            log.info("Nameserver HTTP manage server listening on {}:{}, prefix=/_manage", bindHost, port);
        } catch (Exception ex) {
            shutdown();
            throw new IllegalStateException("启动 Nameserver 管理口失败, port=" + port, ex);
        }
    }

    /** 关闭管理口：关 channel 并优雅停掉 Netty 线程组，可重复调用 */
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
