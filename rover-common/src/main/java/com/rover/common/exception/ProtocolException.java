package com.rover.common.exception;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: 协议编解码出错
 */
public class ProtocolException extends RoverException {

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
