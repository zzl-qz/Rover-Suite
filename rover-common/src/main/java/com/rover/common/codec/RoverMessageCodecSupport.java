package com.rover.common.codec;

import com.rover.common.constants.ProtocolConstants;
import com.rover.common.protocol.AckMode;
import com.rover.common.protocol.CommonResponseBody;
import com.rover.common.protocol.ProtocolFlags;
import com.rover.common.protocol.RoverMessage;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: 拼消息的快捷方法
 *
 * 这个类是什么：RoverMessage 组装与 body 反序列化的静态工具类。
 * 核心职责：统一处理 flags、ackMode、超时与 Protostuff 序列化，提供
 * request/onewayRequest/response/push/decodeBody 快捷方法。
 * 被谁用：NameserverClient、服务端 Listener/Push、Dispatcher 等两端共用。
 */
public final class RoverMessageCodecSupport {

    /** 工具类，禁止实例化 */
    private RoverMessageCodecSupport() {
    }

    /**
     * 构造普通请求消息（默认 IMMEDIATE ack 与默认超时）。
     *
     * @param type      消息类型（如 REGISTER_REQUEST / QUERY_REQUEST 等常量）
     * @param requestId 全局唯一的请求号，用于响应配对
     * @param body      请求体对象，会被 Protostuff 序列化进消息体
     * @return 组装好的 RoverMessage
     */
    public static RoverMessage request(byte type, long requestId, Object body) {
        return request(type, requestId, AckMode.IMMEDIATE, ProtocolConstants.DEFAULT_TIMEOUT_MS, body);
    }

    /**
     * 构造请求消息，可指定 ack 模式与超时。
     *
     * @param type      消息类型
     * @param requestId 请求号
     * @param ackMode   ack 模式，写入 flags
     * @param timeoutMs 期望响应超时（毫秒），写入消息头
     * @param body      请求体对象
     * @return 组装好的 RoverMessage
     */
    public static RoverMessage request(
            byte type, long requestId, AckMode ackMode, int timeoutMs, Object body) {
        // 先把 ackMode 写进 flags，再连同 body 一起组装成帧
        short flags = ProtocolFlags.withAckMode(ProtocolFlags.empty(), ackMode);
        return RoverMessage.of(
                type,
                requestId,
                flags,
                timeoutMs,
                ProtostuffSerializer.serialize(body));
    }

    /**
     * 构造单向请求消息：带上 FLAG_ONEWAY 标志，表示不需要响应。
     *
     * @param type      消息类型
     * @param requestId 请求号
     * @param body      请求体对象
     * @return 组装好的 RoverMessage
     */
    public static RoverMessage onewayRequest(byte type, long requestId, Object body) {
        short flags = ProtocolFlags.enable(ProtocolFlags.empty(), ProtocolFlags.FLAG_ONEWAY);
        return RoverMessage.of(
                type,
                requestId,
                flags,
                ProtocolConstants.DEFAULT_TIMEOUT_MS,
                ProtostuffSerializer.serialize(body));
    }

    /**
     * 构造通用响应消息：回填被响应请求的 requestId，ackMode 取自响应体。
     *
     * @param requestId 对应请求的 requestId
     * @param body      应答体（CommonResponseBody 子类实例）；为 null 时按 IMMEDIATE 处理
     * @return 组装好的 RoverMessage
     */
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

    /**
     * 构造服务端推送消息：以 PUSH_RESPONSE 类型下发，单向、不需要对端应答。
     *
     * @param requestId 推送序号
     * @param body      推送体对象（如 ServicePushBody）
     * @return 组装好的 RoverMessage
     */
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

    /**
     * 将消息体反序列化为指定类型。
     *
     * @param message 待解析消息；为 null 时按空数据解析
     * @param clazz   目标类型
     * @return 反序列化得到的对象
     */
    public static <T> T decodeBody(RoverMessage message, Class<T> clazz) {
        byte[] body = message == null ? null : message.getBody();
        return ProtostuffSerializer.deserialize(body, clazz);
    }
}
