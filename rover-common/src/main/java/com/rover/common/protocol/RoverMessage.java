package com.rover.common.protocol;

import com.rover.common.constants.ProtocolConstants;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-07 09:35:00
 * Description: Rover TCP 协议帧模型，与帧头 22 字节布局一一对应
 */
@Data
public class RoverMessage {

    /** 协议版本 */
    private byte version = ProtocolConstants.VERSION;
    /** 消息类型，见 ProtocolConstants */
    private byte type;
    /** 标志位，见 ProtocolFlags */
    private short flags = ProtocolFlags.empty();
    /** 请求响应配对 ID */
    private long requestId;
    /** 超时毫秒；0 用默认值 */
    private int timeoutMs;
    /** 序列化后的业务 body */
    private byte[] body;

    /** 快捷工厂：默认 flags 与默认超时 */
    public static RoverMessage of(byte type, long requestId, byte[] body) {
        return of(type, requestId, ProtocolFlags.empty(), 0, body);
    }

    /** 全参工厂：指定 flags 与超时（timeoutMs 为 0 时走默认值） */
    public static RoverMessage of(
            byte type, long requestId, short flags, int timeoutMs, byte[] body) {
        RoverMessage message = new RoverMessage();
        message.setVersion(ProtocolConstants.VERSION);
        message.setType(type);
        message.setFlags(flags);
        message.setRequestId(requestId);
        message.setTimeoutMs(timeoutMs);
        message.setBody(body);
        return message;
    }

    /** 读取本消息的 ack 模式（由 flags 低 2 位解析） */
    public AckMode ackMode() {
        return ProtocolFlags.ackModeOf(flags);
    }

    /** 是否置了 FLAG_ONEWAY 位（无需应答） */
    public boolean oneway() {
        return ProtocolFlags.has(flags, ProtocolFlags.FLAG_ONEWAY);
    }

    /** 就地改写 ack 模式并返回自身（null 回落为 SINGLE），便于链式调用 */
    public RoverMessage withAckMode(AckMode ackMode) {
        this.flags = ProtocolFlags.withAckMode(this.flags, ackMode);
        return this;
    }
}
