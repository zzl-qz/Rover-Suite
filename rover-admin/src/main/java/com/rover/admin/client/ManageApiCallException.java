package com.rover.admin.client;

/**
 * Author: Daylight
 * Created: 2026-09-27 22:20:00
 * Description: 调用组件 /_manage 管理口失败：带上游 HTTP 状态码与原始文案
 *
 * 为什么不复用 {@link IllegalArgumentException}：那会把下游的 409（版本冲突）压成 400
 * 「请求参数写错了」，两者的处置完全不同——409 要重新拉取版本再重提，400 要改请求本身。
 * 状态码必须原样传下去，前端才分得清「我看到的版本过期了」和「我参数写错了」。
 *
 * {@code statusCode} 为 0 表示压根没拿到响应（连接失败、超时）；这种既不是下游拒绝，
 * 也不是请求有错，上层应按「下游不可用」处理。
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
