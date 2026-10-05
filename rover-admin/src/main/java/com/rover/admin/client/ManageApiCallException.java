package com.rover.admin.client;

/**
 * Author: Daylight
 * Created: 2026-09-27 22:20:00
 * Description: 管理口调用异常，保留上游 HTTP 状态码与响应文案。
 * statusCode 为 0 表示连接失败或超时，未收到响应。
 */
public class ManageApiCallException extends RuntimeException {

    /** 上游 HTTP 状态码；0 表示没有拿到响应（连接失败/超时/中断）。 */
    private final int statusCode;

    public ManageApiCallException(int statusCode, String message) {
        this(statusCode, message, null);
    }

    public ManageApiCallException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /** @return 上游 HTTP 状态码；0 表示没有拿到响应 */
    public int statusCode() {
        return statusCode;
    }

    /** @return 下游是否给出了响应（用于区分「下游拒绝」与「下游没回应」） */
    public boolean hasResponse() {
        return statusCode > 0;
    }
}
