package com.rover.common.codec;

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
 * Created: 2026-08-06 16:10:00
 * Description: Netty 入站解码器：按协议帧头解析 RoverMessage，基于累积缓冲处理粘包/半包
 */
public class RoverMessageDecoder extends ByteToMessageDecoder {

    /**
     * 解析一条消息；解析前 markReaderIndex，数据不足（半包）时回退并等待下次回调，
     * 魔数/版本/flags/长度非法时回退并抛 ProtocolException 关闭连接。
     */
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // 半包：连帧头都不够，等待更多字节
        if (in.readableBytes() < ProtocolConstants.HEADER_LENGTH) {
            return;
        }

        // 记住本次解析起点，后续任何"数据不足/校验失败"都回退到这里
        in.markReaderIndex();
        // 魔数校验：2 字节，防止把乱流误当协议帧
        int magic = in.readUnsignedShort();
        if (magic != ProtocolConstants.MAGIC_NUMBER) {
            in.resetReaderIndex();
            throw new ProtocolException(
                    "非法协议魔数: 0x" + Integer.toHexString(magic).toUpperCase());
        }

        // 版本校验：1 字节，前后端协议不兼容时直接报错
        byte version = in.readByte();
        if (version != ProtocolConstants.VERSION) {
            in.resetReaderIndex();
            throw new ProtocolException("不支持的协议版本: " + version);
        }

        byte type = in.readByte();
        // flags：2 字节，含 ack 模式与 oneway 等标志位
        short flags = in.readShort();
        try {
            ProtocolFlags.validateSupported(flags);
        } catch (IllegalArgumentException ex) {
            in.resetReaderIndex();
            throw new ProtocolException(ex.getMessage(), ex);
        }

        // requestId：8 字节，客户端据此把响应与在途请求配对
        long requestId = in.readLong();
        // timeoutMs：4 字节，负值视为非法
        int timeoutMs = in.readInt();
        if (timeoutMs < 0) {
            in.resetReaderIndex();
            throw new ProtocolException("非法超时时间: " + timeoutMs);
        }

        // bodyLength：4 字节，限制单帧消息体大小，防止超大包拖垮内存
        int bodyLength = in.readInt();
        if (bodyLength < 0 || bodyLength > ProtocolConstants.MAX_BODY_LENGTH) {
            in.resetReaderIndex();
            throw new ProtocolException("非法消息体长度: " + bodyLength);
        }

        // body 没收齐则回退，等待下次回调补齐
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
