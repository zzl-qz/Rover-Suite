package com.rover.nameserver.client.codec;

import com.rover.common.constants.ProtocolConstants;
import com.rover.common.protocol.AckMode;
import com.rover.common.protocol.CommonResponseBody;
import com.rover.common.protocol.ProtocolFlags;
import com.rover.common.protocol.RoverMessage;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: 拼消息的快捷方法
 */
public final class RoverMessageCodecSupport {

    private RoverMessageCodecSupport() {
    }

    public static RoverMessage request(byte type, long requestId, Object body) {
        return request(type, requestId, AckMode.IMMEDIATE, ProtocolConstants.DEFAULT_TIMEOUT_MS, body);
    }

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

    public static RoverMessage onewayRequest(byte type, long requestId, Object body) {
        short flags = ProtocolFlags.enable(ProtocolFlags.empty(), ProtocolFlags.FLAG_ONEWAY);
        return RoverMessage.of(
                type,
                requestId,
                flags,
                ProtocolConstants.DEFAULT_TIMEOUT_MS,
                ProtostuffSerializer.serialize(body));
    }

    public static RoverMessage response(long requestId, CommonResponseBody body) {
        short flags = ProtocolFlags.withAckMode(
                ProtocolFlags.empty(),
                AckMode.fromCode(body == null ? AckMode.IMMEDIATE.getCode() : body.getAppliedAckMode()));
        return RoverMessage.of(
                ProtocolConstants.COMMON_RESPONSE,
                requestId,
                flags,
                0,
                ProtostuffSerializer.serialize(body));
    }

    public static RoverMessage push(long requestId, Object body) {
        // 推送一般不指望对方再回一包
        short flags = ProtocolFlags.enable(ProtocolFlags.empty(), ProtocolFlags.FLAG_ONEWAY);
        return RoverMessage.of(
                ProtocolConstants.PUSH_RESPONSE,
                requestId,
                flags,
                0,
                ProtostuffSerializer.serialize(body));
    }

    public static <T> T decodeBody(RoverMessage message, Class<T> clazz) {
        byte[] body = message == null ? null : message.getBody();
        return ProtostuffSerializer.deserialize(body, clazz);
    }
}
