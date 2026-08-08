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
 */
public class RoverMessageEncoder extends MessageToByteEncoder<RoverMessage> {

    @Override
    protected void encode(ChannelHandlerContext ctx, RoverMessage msg, ByteBuf out) {
        if (msg == null) {
            throw new ProtocolException("编码消息不能为空");
        }
        byte[] body = msg.getBody();
        int bodyLength = body == null ? 0 : body.length;
        if (bodyLength > ProtocolConstants.MAX_BODY_LENGTH) {
            throw new ProtocolException("消息体过大: " + bodyLength);
        }

        short flags = msg.getFlags();
        try {
            ProtocolFlags.validateSupported(flags);
        } catch (IllegalArgumentException ex) {
            throw new ProtocolException(ex.getMessage(), ex);
        }

        byte version = msg.getVersion() == 0 ? ProtocolConstants.VERSION : msg.getVersion();
        int timeoutMs = Math.max(msg.getTimeoutMs(), 0);

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
