package com.rover.gateway.core.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

/**
 * 入站 Keep-Alive 读空闲就关。占着 BUSY 说明这根管子上还有单，空闲回收别误杀。
 * 请求中客户端半截不送，另有 requestIdleTimeout，不走这里。
 */
final class InboundIdleCloser extends ChannelInboundHandlerAdapter {

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent idle
                && idle.state() == IdleState.READER_IDLE
                && !GatewayHttpServerHandler.isOccupied(ctx.channel())) {
            ctx.close();
            return;
        }
        ctx.fireUserEventTriggered(evt);
    }
}
