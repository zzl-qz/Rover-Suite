package com.rover.common.exception;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: 协议编解码出错
 *
 * 这个类是什么：协议层专用异常，继承自 RoverException。
 * 核心职责：标记报文拆包/组包/字段校验过程中的失败(如魔数不对、长度非法、
 * body 反序列化失败)，让连接层据此决定丢弃帧或断开连接。
 * 被谁用：rover-common/protocol 与各模块编解码器，在解析帧时抛出。
 */
public class ProtocolException extends RoverException {

    /** 带错误消息构造 */
    public ProtocolException(String message) {
        super(message);
    }

    /** 带错误消息与根因构造 */
    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
