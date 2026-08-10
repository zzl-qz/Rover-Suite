package com.rover.common.exception;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 运行时异常基类
 *
 * 这个类是什么：Rover 全部业务异常的公共基类。
 * 核心职责：统一异常体系，让上层 catch(RoverException) 即可兜住框架所有业务异常；
 * 同时提供无参/带消息/带原因等全套构造。
 * 被谁用：GatewayException、RegistryException、ProtocolException 等具体异常的父类。
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
