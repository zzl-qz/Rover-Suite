package com.rover.common.exception;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 运行时异常基类
 */
public class RoverException extends RuntimeException {

    /** 无参构造 */
    public RoverException() {
    }

    /** 带错误消息构造 */
    public RoverException(String message) {
        super(message);
    }

    /** 带错误消息与根因构造 */
    public RoverException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 仅带根因构造 */
    public RoverException(Throwable cause) {
        super(cause);
    }
}
