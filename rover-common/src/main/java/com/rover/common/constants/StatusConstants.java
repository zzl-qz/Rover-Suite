package com.rover.common.constants;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 维护统一响应状态码常量
 */
public final class StatusConstants {

    private StatusConstants() {
    }

    public static final int SUCCESS = 200;
    public static final int BAD_REQUEST = 400;
    public static final int SERVICE_NOT_FOUND = 404;
    public static final int SERVER_ERROR = 500;
}
