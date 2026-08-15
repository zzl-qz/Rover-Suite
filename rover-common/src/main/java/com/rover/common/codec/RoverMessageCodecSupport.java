package com.rover.common.codec;

import com.rover.common.constants.ProtocolConstants;
import com.rover.common.protocol.AckMode;
import com.rover.common.protocol.CommonResponseBody;
import com.rover.common.protocol.ProtocolFlags;
import com.rover.common.protocol.RoverMessage;

/**
 * Author: Daylight
 * Created: 2026-08-06 16:15:00
 * Description: RoverMessage 组装与 body 反序列化静态工具，两端共用
 */
public final class RoverMessageCodecSupport {

    /** 工具类，禁止实例化 */
    private RoverMessageCodecSupport() {
    }

    /** 构造普通请求消息（默认 SINGLE ack 与默认超时）。 */
    public static RoverMessage request(byte type, long requestId, Object body) {
        return request(type, requestId, AckMode.SINGLE, ProtocolConstants.DEFAULT_TIMEOUT_MS, body);
    }

    /** 构造请求消息，可指定 ack 模式与超时。 */
    public static RoverMessage request(
            byte type, long requestId, AckMode ackMode, int timeoutMs, Object body) {
        short flags = ProtocolFlags.withAckMode(ProtocolFlags.empty(), ackMode);
        return RoverMessage.of(
                type,
                requestId,
                flags,
                timeoutMs,
                ProtostuffSerializer.serialize(body));
    }

    /** 构造单向请求消息：置 FLAG_ONEWAY，无需对端响应。 */
    public static RoverMessage onewayRequest(byte type, long requestId, Object body) {
        short flags = ProtocolFlags.enable(ProtocolFlags.empty(), ProtocolFlags.FLAG_ONEWAY);
        return RoverMessage.of(
                type,
                requestId,
                flags,
                ProtocolConstants.DEFAULT_TIMEOUT_MS,
                ProtostuffSerializer.serialize(body));
    }

    /** 构造通用响应消息：回填被响应请求的 requestId，ackMode 取自响应体。 */
    public static RoverMessage response(long requestId, CommonResponseBody body) {
        short flags = ProtocolFlags.withAckMode(
                ProtocolFlags.empty(),
                AckMode.fromCode(body == null ? AckMode.SINGLE.getCode() : body.getAppliedAckMode()));
        return RoverMessage.of(
                ProtocolConstants.COMMON_RESPONSE,
                requestId,
                flags,
                0,
                ProtostuffSerializer.serialize(body));
    }

    /** 构造服务端推送消息：PUSH_RESPONSE 类型、单向，不需要对端应答。 */
    public static RoverMessage push(long requestId, Object body) {
        // 推送为单向消息，无需对端应答
        short flags = ProtocolFlags.enable(ProtocolFlags.empty(), ProtocolFlags.FLAG_ONEWAY);
        return RoverMessage.of(
                ProtocolConstants.PUSH_RESPONSE,
                requestId,
                flags,
                0,
                ProtostuffSerializer.serialize(body));
    }

    /** 将消息体反序列化为指定类型；message 为 null 时按空数据解析。 */
    public static <T> T decodeBody(RoverMessage message, Class<T> clazz) {
        byte[] body = message == null ? null : message.getBody();
        return ProtostuffSerializer.deserialize(body, clazz);
    }
}
