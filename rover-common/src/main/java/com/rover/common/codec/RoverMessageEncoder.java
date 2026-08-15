package com.rover.common.codec;

import com.rover.common.constants.ProtocolConstants;
import com.rover.common.exception.ProtocolException;
import com.rover.common.protocol.ProtocolFlags;
import com.rover.common.protocol.RoverMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Author: Daylight
 * Created: 2026-08-06 16:05:00
 * Description: Netty 出站编码器：将 RoverMessage 按协议帧格式写入 ByteBuf，写出前校验 body 长度与 flags
 */
public class RoverMessageEncoder extends MessageToByteEncoder<RoverMessage> {

    /** 编码单条消息为帧字节；消息为 null、body 超限或 flags 非法时抛 ProtocolException。 */
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
