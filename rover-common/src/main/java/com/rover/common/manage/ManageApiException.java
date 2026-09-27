package com.rover.common.manage;

import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Author: Daylight
 * Created: 2026-09-27 14:40:00
 * Description: 带 HTTP 状态码的管理口异常：骨架按 status 返回，而不是一律 400
 *
 * 典型场景是乐观锁冲突：调用方需要区分「参数写错了」（400，改了再发）与
 * 「别人先改了」（409，得先刷新再决定），并把当前版本号一并带回去。
 */
public class ManageApiException extends RuntimeException {

    /** 返回给调用方的状态码。 */
    private final transient HttpResponseStatus status;

    /** 附加字段，会平铺进响应 JSON，如 currentRevision。 */
    private final transient Map<String, Object> details;

    public ManageApiException(HttpResponseStatus status, String message) {
        this(status, message, Map.of());
    }

    public ManageApiException(HttpResponseStatus status, String message, Map<String, Object> details) {
        super(message);
        this.status = status == null ? HttpResponseStatus.BAD_REQUEST : status;
        this.details = details == null ? Map.of() : new LinkedHashMap<>(details);
    }

    public HttpResponseStatus getStatus() {
        return status;
    }

    /** @return 附加字段副本，调用方直接塞进响应体。 */
    public Map<String, Object> getDetails() {
        return new LinkedHashMap<>(details);
    }
}
