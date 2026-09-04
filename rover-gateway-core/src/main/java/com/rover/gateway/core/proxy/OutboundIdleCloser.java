package com.rover.gateway.core.proxy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

/**
 * 出站池里读空闲就关。还挂着 Exchange 说明正在等上游，别踢。
 */
final class OutboundIdleCloser extends ChannelInboundHandlerAdapter {

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent idle
                && idle.state() == IdleState.READER_IDLE
                && ctx.channel().attr(NettyUpstreamClient.EXCHANGE).get() == null) {
            ctx.close();
            return;
        }
        ctx.fireUserEventTriggered(evt);
    }
}
