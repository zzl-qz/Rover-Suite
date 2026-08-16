package com.rover.common.constants;

/**
 * Author: Daylight
 * Created: 2026-08-01 09:15:00
 * Description: 统一业务响应状态码，语义对照 HTTP 状态码
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
    /** 鉴权失败 */
    public static final int UNAUTHORIZED = 401;
    /** 服务端内部错误 */
    public static final int SERVER_ERROR = 500;
}
