package com.rover.common.protocol;

import com.rover.common.constants.ProtocolConstants;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: 一帧协议消息
 *
 * 这个类是什么：Rover TCP 协议在内存中的帧模型，与帧头 22 字节布局一一对应。
 * 核心职责：承载一条消息的版本/类型/flags/requestId/超时/body，并为常见场景提供
 * 工厂方法与便捷查询。
 * 被谁用：客户端与服务端的编解码器(序列化/反序列化)；业务层构造与解析消息。
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

    /**
     * 快捷工厂：用默认 flags 与默认超时构造消息。
     *
     * @param type      消息类型，见 ProtocolConstants
     * @param requestId 请求响应配对 ID
     * @param body      序列化后的业务 body
     * @return 组装好的消息帧
     */
    public static RoverMessage of(byte type, long requestId, byte[] body) {
        return of(type, requestId, ProtocolFlags.empty(), 0, body);
    }

    /**
     * 全参工厂：指定 flags 与超时构造消息。
     *
     * @param type      消息类型，见 ProtocolConstants
     * @param requestId 请求响应配对 ID
     * @param flags     帧标志位，见 ProtocolFlags
     * @param timeoutMs 超时毫秒，0 表示走默认值
     * @param body      序列化后的业务 body
     * @return 组装好的消息帧
     */
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

    /**
     * 读取本消息的 ack 模式。
     *
     * @return 由 flags 低 2 位解析出的 AckMode
     */
    public AckMode ackMode() {
        return ProtocolFlags.ackModeOf(flags);
    }

    /**
     * 判断本消息是否为 oneway(无需应答)。
     *
     * @return true 表示置了 FLAG_ONEWAY 位
     */
    public boolean oneway() {
        return ProtocolFlags.has(flags, ProtocolFlags.FLAG_ONEWAY);
    }

    /**
     * 就地改写 ack 模式并返回自身。
     *
     * @param ackMode 新的 ack 模式；null 回落为 IMMEDIATE
     * @return this，便于链式调用
     */
    public RoverMessage withAckMode(AckMode ackMode) {
        this.flags = ProtocolFlags.withAckMode(this.flags, ackMode);
        return this;
    }
}
