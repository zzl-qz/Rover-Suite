package com.rover.common.protocol;

import com.rover.common.constants.ProtocolConstants;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: 一帧协议消息
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

    public static RoverMessage of(byte type, long requestId, byte[] body) {
        return of(type, requestId, ProtocolFlags.empty(), 0, body);
    }

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

    public AckMode ackMode() {
        return ProtocolFlags.ackModeOf(flags);
    }

    public boolean oneway() {
        return ProtocolFlags.has(flags, ProtocolFlags.FLAG_ONEWAY);
    }

    public RoverMessage withAckMode(AckMode ackMode) {
        this.flags = ProtocolFlags.withAckMode(this.flags, ackMode);
        return this;
    }
}
