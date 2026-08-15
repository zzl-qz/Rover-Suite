package com.rover.common.exception;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: 协议编解码出错
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
