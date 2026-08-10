package com.rover.nameserver.core.server;

import com.rover.common.exception.ProtocolException;
import com.rover.common.protocol.RoverMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:50:00
 * Description: Nameserver 连接处理器
 *
 * 核心职责：Netty pipeline 的末端业务 Handler，承接连接生命周期事件
 * （建立/断开/异常）与解码后的协议消息，是网络层与业务分发层之间的桥。</p>
 *
 * 被 {@link NameserverTcpServer} 挂到 {@code ChannelInitializer} 的 pipeline 上，
 * 且运行在独立业务线程组（bizGroup）上，避免业务耗时阻塞 IO 线程。
 * 业务真正执行交给 {@link NameserverRequestDispatcher}——本类保持轻薄，
 * 只做事件转发与日志。</p>
 *
 * 消息流：RoverMessageDecoder 解码 → 本 Handler 的 channelRead0 →
 * dispatcher.dispatch → 响应经 RoverMessageEncoder 编码返回。</p>
 */
@Slf4j
public class NameserverServerHandler extends SimpleChannelInboundHandler<RoverMessage> {

    /** 业务分发器：处理各类协议消息与断线清理 */
    private final NameserverRequestDispatcher dispatcher;

    /**
     * 构造处理器。
     *
     * @param dispatcher 请求分发器（dispatch + onChannelInactive）
     */
    public NameserverServerHandler(NameserverRequestDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /** 新连接建立：记录日志（连接生命周期入口） */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.info("客户端已连接: {}", ctx.channel().remoteAddress());
    }

    /** 解码完成的消息到达：转交分发器，按类型处理 */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RoverMessage msg) {
        dispatcher.dispatch(ctx.channel(), msg);
    }

    /** 连接断开：转分发器做订阅清理与实例摘除（断线恢复） */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("客户端断开: {}", ctx.channel().remoteAddress());
        dispatcher.onChannelInactive(ctx.channel());
    }

    /**
     * 处理异常：协议错误（解码失败等）与普通异常分开记录，
     * 出现异常统一关闭连接——协议状态已不可信，继续复用风险更大。
     */
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