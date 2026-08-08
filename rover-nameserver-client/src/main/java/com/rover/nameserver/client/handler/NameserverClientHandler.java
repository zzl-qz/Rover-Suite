package com.rover.nameserver.client.handler;

import com.rover.common.constants.ProtocolConstants;
import com.rover.common.exception.ProtocolException;
import com.rover.common.protocol.CommonResponseBody;
import com.rover.common.protocol.RoverMessage;
import com.rover.common.protocol.ServicePushBody;
import com.rover.nameserver.client.cache.InstanceCache;
import com.rover.nameserver.client.codec.RoverMessageCodecSupport;
import com.rover.nameserver.client.connection.NameserverClient;
import com.rover.common.concurrent.PendingRequestTable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:55:00
 * Description: 客户端收包处理
 */
@Slf4j
public class NameserverClientHandler extends SimpleChannelInboundHandler<RoverMessage> {

    private final PendingRequestTable<CommonResponseBody> pendingRequests;
    private final InstanceCache instanceCache;
    private final NameserverClient client;

    public NameserverClientHandler(
            PendingRequestTable<CommonResponseBody> pendingRequests,
            InstanceCache instanceCache,
            NameserverClient client) {
        this.pendingRequests = pendingRequests;
        this.instanceCache = instanceCache;
        this.client = client;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RoverMessage msg) {
        if (msg.getType() == ProtocolConstants.PUSH_RESPONSE) {
            ServicePushBody pushBody = RoverMessageCodecSupport.decodeBody(msg, ServicePushBody.class);
            instanceCache.onPush(pushBody);
            return;
        }

        if (msg.getType() == ProtocolConstants.COMMON_RESPONSE) {
            CommonResponseBody body = RoverMessageCodecSupport.decodeBody(msg, CommonResponseBody.class);
            boolean matched = pendingRequests.complete(msg.getRequestId(), body);
            if (!matched) {
                log.debug("收到过期或未知响应, requestId={}", msg.getRequestId());
            }
            return;
        }

        log.warn("客户端收到未处理消息类型: {}", msg.getType());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.warn("与 Nameserver 连接断开: {}", ctx.channel().remoteAddress());
        pendingRequests.failAll(new IllegalStateException("连接已断开"));
        client.onDisconnected();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof ProtocolException) {
            log.warn("客户端协议错误: {}", cause.getMessage());
        } else {
            log.warn("客户端连接异常", cause);
        }
        ctx.close();
    }
}
