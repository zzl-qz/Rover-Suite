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
 */
@Slf4j
public class NameserverHttpManageServer {

    private final int port;
    private final NameserverManageApi manageApi;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NameserverHttpManageServer(int port, NameserverRuntime runtime) {
        this.port = port;
        this.manageApi = new NameserverManageApi(runtime);
    }

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
