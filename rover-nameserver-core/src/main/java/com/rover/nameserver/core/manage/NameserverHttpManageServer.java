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
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:45:00
 * Description: Nameserver 旁路 HTTP 管理口
 *
 * 这个类是什么：基于 Netty 的轻量 HTTP 服务端，与 TCP 注册口并行监听。
 * 核心职责：①绑定 managePort 接收 HTTP 请求；②把解码后的请求转给 NameserverManageApi；
 * ③提供 start/shutdown 生命周期管理。
 * 被谁用：NameserverTcpServer 在 TCP 服务启动时一并启停；port<=0 时直接跳过不监听。
 */
@Slf4j
public class NameserverHttpManageServer {

    /** HTTP 管理口监听端口，0 或未配置表示不启用 */
    private final int port;
    /** 管理 API 处理器，负责路由与 JSON 响应 */
    private final NameserverManageApi manageApi;

    /** Netty boss 线程组，负责 accept */
    private EventLoopGroup bossGroup;
    /** Netty worker 线程组，负责 IO */
    private EventLoopGroup workerGroup;
    /** 服务端 channel，关闭时使用 */
    private Channel serverChannel;

    /**
     * @param port    HTTP 管理口端口
     * @param runtime Nameserver 运行时，供 ManageApi 读状态与改配置
     */
    public NameserverHttpManageServer(int port, NameserverRuntime runtime) {
        this.port = port;
        this.manageApi = new NameserverManageApi(runtime);
    }

    /**
     * 启动 HTTP 管理口；port<=0 时只打日志并返回。
     *
     * @throws IllegalStateException 绑定端口失败
     */
    public void start() {
        if (port <= 0) {
            log.info("Nameserver managePort 未启用");
            return;
        }
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(1);
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(new HttpServerCodec())
                                    .addLast(new HttpObjectAggregator(256 * 1024))
                                    .addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
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
            serverChannel = bootstrap.bind(port).sync().channel();
            log.info("Nameserver HTTP manage server listening on port {}, prefix=/_manage", port);
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
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
    }
}
