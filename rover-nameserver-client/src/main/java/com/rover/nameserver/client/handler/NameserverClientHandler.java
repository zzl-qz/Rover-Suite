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
 *
 * 核心职责：位于客户端 Netty 流水线（Decoder -> Encoder -> 本 handler）的最内层，
 * 处理所有入站 RoverMessage：服务端推送（PUSH_RESPONSE）落到 InstanceCache 并通知
 * 监听器；RPC 响应（COMMON_RESPONSE）按 requestId 到 PendingRequestTable 里 complete
 * 对应的 Future，完成请求响应的配对；连接断开时快速失败在途请求并通知
 * NameserverClient 进入重连状态。由 NameserverClient.connect 创建。
 */
@Slf4j
public class NameserverClientHandler extends SimpleChannelInboundHandler<RoverMessage> {

    /** 在途请求表，与 NameserverClient 共享同一实例，按 requestId 配对响应 */
    private final PendingRequestTable<CommonResponseBody> pendingRequests;
    /** 本地实例缓存，推送到达时写入 */
    private final InstanceCache instanceCache;
    /** 所属客户端，断开时回调其重连入口 */
    private final NameserverClient client;

    /**
     * 构造 handler，与客户端共享在途请求表与缓存。
     *
     * @param pendingRequests 在途请求表（请求响应配对用）
     * @param instanceCache   本地实例缓存（推送落地用）
     * @param client          所属客户端（断线通知用）
     */
    public NameserverClientHandler(
            PendingRequestTable<CommonResponseBody> pendingRequests,
            InstanceCache instanceCache,
            NameserverClient client) {
        this.pendingRequests = pendingRequests;
        this.instanceCache = instanceCache;
        this.client = client;
    }

    /**
     * 处理一条完整解码后的入站消息，按类型分派：
     * 推送 -> 更新缓存；RPC 响应 -> 按 requestId 完成对应 Future。
     *
     * @param ctx 通道上下文
     * @param msg 解码后的一条 RoverMessage
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RoverMessage msg) {
        // 服务端主动推：更新缓存，不走 pending
        if (msg.getType() == ProtocolConstants.PUSH_RESPONSE) {
            ServicePushBody pushBody = RoverMessageCodecSupport.decodeBody(msg, ServicePushBody.class);
            instanceCache.onPush(pushBody);
            return;
        }

        // 普通 RPC 响应：按 requestId 唤醒等待中的 Future
        if (msg.getType() == ProtocolConstants.COMMON_RESPONSE) {
            CommonResponseBody body = RoverMessageCodecSupport.decodeBody(msg, CommonResponseBody.class);
            // 配对失败说明是超时后迟到的响应或未知请求号的响应，丢弃即可
            boolean matched = pendingRequests.complete(msg.getRequestId(), body);
            if (!matched) {
                log.debug("收到过期或未知响应, requestId={}", msg.getRequestId());
            }
            return;
        }

        log.warn("客户端收到未处理消息类型: {}", msg.getType());
    }

    /**
     * 连接断开：先让所有在途请求立刻失败（避免业务线程一直卡在 get() 等到超时），
     * 再通知客户端进入待重连状态。
     *
     * @param ctx 通道上下文
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.warn("与 Nameserver 连接断开: {}", ctx.channel().remoteAddress());
        // 在途请求立刻失败，避免业务线程一直卡在 get()
        pendingRequests.failAll(new IllegalStateException("连接已断开"));
        client.onDisconnected();
    }

    /**
     * 收包/处理过程异常：协议错误只打警告，其余记完整异常栈，随后关闭连接
     * （触发 channelInactive 走统一的重连流程）。
     *
     * @param ctx   通道上下文
     * @param cause 异常原因
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof ProtocolException) {
            // 协议级错误（读到了非法帧等），消息本身即足够定位，不用刷完整栈
            log.warn("客户端协议错误: {}", cause.getMessage());
        } else {
            log.warn("客户端连接异常", cause);
        }
        ctx.close();
    }
}
