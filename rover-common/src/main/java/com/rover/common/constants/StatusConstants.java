package com.rover.common.constants;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 维护统一响应状态码常量
 *
 * 这个类是什么：Rover 统一业务状态码常量表，语义对照 HTTP 状态码。
 * 核心职责：让网关/注册中心/客户端对「成功、参数错误、服务不存在、服务端异常」
 * 用同一套码值沟通，避免各模块自造状态码。
 * 被谁用：CommonResponseBody、Result、网关与注册中心的响应构造代码。
 */
public final class StatusConstants {

    /** 工具类不允许实例化 */
    private StatusConstants() {
    }

    /** 成功 */
    public static final int SUCCESS = 200;
    /** 请求参数错误 */
    public static final int BAD_REQUEST = 400;
    /** 目标服务不存在 */
    public static final int SERVICE_NOT_FOUND = 404;
    /** 服务端内部错误 */
    public static final int SERVER_ERROR = 500;
}
