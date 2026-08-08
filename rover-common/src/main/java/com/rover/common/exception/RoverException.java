package com.rover.common.exception;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 运行时异常基类
 */
public class RoverException extends RuntimeException {

    public RoverException() {
    }

    public RoverException(String message) {
        super(message);
    }

    public RoverException(String message, Throwable cause) {
        super(message, cause);
    }

    public RoverException(Throwable cause) {
        super(cause);
    }
}
