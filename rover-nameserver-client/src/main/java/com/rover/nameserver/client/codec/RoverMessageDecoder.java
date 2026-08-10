package com.rover.nameserver.client.codec;

import com.rover.common.constants.ProtocolConstants;
import com.rover.common.exception.ProtocolException;
import com.rover.common.protocol.ProtocolFlags;
import com.rover.common.protocol.RoverMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: 消息解码，顺手处理粘包半包
 */
public class RoverMessageDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // 半包：头都不够，等更多字节
        if (in.readableBytes() < ProtocolConstants.HEADER_LENGTH) {
            return;
        }

        in.markReaderIndex();
        int magic = in.readUnsignedShort();
        if (magic != ProtocolConstants.MAGIC_NUMBER) {
            in.resetReaderIndex();
            throw new ProtocolException(
                    "非法协议魔数: 0x" + Integer.toHexString(magic).toUpperCase());
        }

        byte version = in.readByte();
        if (version != ProtocolConstants.VERSION) {
            in.resetReaderIndex();
            throw new ProtocolException("不支持的协议版本: " + version);
        }

        byte type = in.readByte();
        short flags = in.readShort();
        try {
            ProtocolFlags.validateSupported(flags);
        } catch (IllegalArgumentException ex) {
            in.resetReaderIndex();
            throw new ProtocolException(ex.getMessage(), ex);
        }

        long requestId = in.readLong();
        int timeoutMs = in.readInt();
        if (timeoutMs < 0) {
            in.resetReaderIndex();
            throw new ProtocolException("非法超时时间: " + timeoutMs);
        }

        int bodyLength = in.readInt();
        if (bodyLength < 0 || bodyLength > ProtocolConstants.MAX_BODY_LENGTH) {
            in.resetReaderIndex();
            throw new ProtocolException("非法消息体长度: " + bodyLength);
        }

        // body 没收齐就回退，等下次
        if (in.readableBytes() < bodyLength) {
            in.resetReaderIndex();
            return;
        }

        byte[] body = null;
        if (bodyLength > 0) {
            body = new byte[bodyLength];
            in.readBytes(body);
        }

        RoverMessage message = new RoverMessage();
        message.setVersion(version);
        message.setType(type);
        message.setFlags(flags);
        message.setRequestId(requestId);
        message.setTimeoutMs(timeoutMs);
        message.setBody(body);
        out.add(message);
    }
}
