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
 *
 * 核心职责：Netty 入站解码器，将字节流按协议帧头解析为 RoverMessage。
 * 基于 {@link ByteToMessageDecoder} 的累积缓冲机制处理 TCP 粘包/半包：帧头不足或
 * 消息体未收齐时直接 return，保留 readerIndex，等下次可读字节到达后再解析。
 *
 * 帧格式（22 字节帧头 + 可选消息体）：magic(2) + version(1) + type(1) + flags(2)
 * + requestId(8) + timeoutMs(4) + bodyLength(4)。任何字段非法（魔数错误、版本不符、
 * flags 不支持、超时/长度越界）都会 resetReaderIndex 后抛 ProtocolException。
 */
public class RoverMessageDecoder extends ByteToMessageDecoder {

    /**
     * 尝试从可读字节中解析出一条消息。
     *
     * 解析之前先 markReaderIndex，中途发现数据不足或字段非法时 resetReaderIndex
     * 回到本次解析的起点；数据不足属于正常半包（return 等待下次回调），字段非法
     * 属于协议错误（抛异常关闭连接）。
     *
     * @param ctx 通道上下文
     * @param in  累积了已收字节的输入缓冲
     * @param out 解析出的 RoverMessage 会添加到这里，交给后续 handler
     * @throws ProtocolException 协议头非法时抛出
     */
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // 半包：头都不够，等更多字节
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
            // 校验 flags 中是否有未注册/不允许的组合
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

        // body 没收齐就回退，等下次
        if (in.readableBytes() < bodyLength) {
            in.resetReaderIndex();
            return;
        }

        // 读走消息体（可为 0 字节，即无 body）
        byte[] body = null;
        if (bodyLength > 0) {
            body = new byte[bodyLength];
            in.readBytes(body);
        }

        // 组装消息对象，交给上层 handler
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
