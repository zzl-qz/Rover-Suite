package com.rover.nameserver.client.codec;

import com.rover.common.constants.ProtocolConstants;
import com.rover.common.exception.ProtocolException;
import com.rover.common.protocol.ProtocolFlags;
import com.rover.common.protocol.RoverMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: 消息编码
 *
 * 这个类是什么：Netty 出站编码器，将 RoverMessage 按协议帧格式写入 ByteBuf。
 * 核心职责：按 magic/version/type/flags/requestId/timeout/body 顺序写帧，
 * 写出前校验 body 长度与 flags 合法性。
 * 被谁用：NameserverClient 连接 pipeline，位于 Decoder 之后、Handler 之前。
 */
public class RoverMessageEncoder extends MessageToByteEncoder<RoverMessage> {

    /**
     * 将单条消息编码为帧字节写入 out。
     *
     * @param ctx 通道上下文
     * @param msg 待发送消息；为 null 时抛协议异常
     * @param out 存放帧字节的输出缓冲
     * @throws ProtocolException 消息为 null、消息体超限或 flags 非法时抛出
     */
    @Override
    protected void encode(ChannelHandlerContext ctx, RoverMessage msg, ByteBuf out) {
        if (msg == null) {
            throw new ProtocolException("编码消息不能为空");
        }
        // 消息体长度取实际字节数，写出前先做上限校验
        byte[] body = msg.getBody();
        int bodyLength = body == null ? 0 : body.length;
        if (bodyLength > ProtocolConstants.MAX_BODY_LENGTH) {
            throw new ProtocolException("消息体过大: " + bodyLength);
        }

        // 出站前同样校验 flags 合法性，避免发出非法帧
        short flags = msg.getFlags();
        try {
            ProtocolFlags.validateSupported(flags);
        } catch (IllegalArgumentException ex) {
            throw new ProtocolException(ex.getMessage(), ex);
        }

        // version 未显式设置时写默认版本；timeout 负值归零
        byte version = msg.getVersion() == 0 ? ProtocolConstants.VERSION : msg.getVersion();
        int timeoutMs = Math.max(msg.getTimeoutMs(), 0);

        // 按帧头顺序写：魔数 -> 版本 -> 类型 -> flags -> requestId -> 超时 -> 长度 -> body
        out.writeShort(ProtocolConstants.MAGIC_NUMBER);
        out.writeByte(version);
        out.writeByte(msg.getType());
        out.writeShort(flags);
        out.writeLong(msg.getRequestId());
        out.writeInt(timeoutMs);
        out.writeInt(bodyLength);
        if (bodyLength > 0) {
            out.writeBytes(body);
        }
    }
}
