package com.rover.nameserver.core.server;

import com.rover.common.exception.ProtocolException;
import com.rover.common.protocol.RoverMessage;
import com.rover.nameserver.core.metrics.NameserverMetricsRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-09 16:45:00
 * Description: Netty 末端 Handler：消息转交 Dispatcher 映射为事件发布，本类不包含注册/推送业务逻辑
 */
@Slf4j
public class NameserverServerHandler extends SimpleChannelInboundHandler<RoverMessage> {

    /** 业务分发器：处理各类协议消息与断线清理 */
    private final NameserverRequestDispatcher dispatcher;
    /** 指标注册表：TCP 连接数实时增减 */
    private final NameserverMetricsRegistry metrics;

    public NameserverServerHandler(NameserverRequestDispatcher dispatcher) {
        this(dispatcher, null);
    }

    public NameserverServerHandler(NameserverRequestDispatcher dispatcher, NameserverMetricsRegistry metrics) {
        this.dispatcher = dispatcher;
        this.metrics = metrics == null ? new NameserverMetricsRegistry() : metrics;
    }

    /** 新连接建立：记录日志并累加连接数 */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        metrics.connectionOpened();
        log.info("客户端已连接: {}", ctx.channel().remoteAddress());
    }

    /** 解码完成的消息到达：转交分发器按类型处理 */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RoverMessage msg) {
        dispatcher.dispatch(ctx.channel(), msg);
    }

    /** 连接断开：扣减连接数并转分发器做订阅清理与实例摘除 */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        metrics.connectionClosed();
        log.info("客户端断开: {}", ctx.channel().remoteAddress());
        dispatcher.onChannelInactive(ctx.channel());
    }

    /** 协议错误与普通异常分开记录；协议状态已不可信，统一关闭连接。 */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof ProtocolException) {
            log.warn("协议错误, 关闭连接 {}: {}", ctx.channel().remoteAddress(), cause.getMessage());
        } else {
            log.warn("连接异常, 关闭连接 {}", ctx.channel().remoteAddress(), cause);
        }
        ctx.close();
    }
}