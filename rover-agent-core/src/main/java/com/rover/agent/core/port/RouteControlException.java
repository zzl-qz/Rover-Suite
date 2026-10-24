package com.rover.agent.core.port;

/**
 * 路由控制异常：CONFLICT 为版本冲突，REJECTED 为请求非法，FAILED 为落盘失败。
 * UNAVAILABLE 表示未收到可信提交结果（含可能已提交的 5xx），须按原 operationId 确认，不得换号重提。
 */
public class RouteControlException extends RuntimeException {

    /** 失败性质。 */
    public enum Kind { CONFLICT, REJECTED, FAILED, UNAVAILABLE }

    private final Kind kind;
    /** 冲突时网关给出的当前版本；未知为 -1。 */
    private final int currentRevision;

    public RouteControlException(Kind kind, String message) {
        this(kind, message, -1, null);
    }

    public RouteControlException(Kind kind, String message, Throwable cause) {
        this(kind, message, -1, cause);
    }

    public RouteControlException(Kind kind, String message, int currentRevision) {
        this(kind, message, currentRevision, null);
    }

    public RouteControlException(Kind kind, String message, int currentRevision, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.currentRevision = currentRevision;
    }

    public Kind kind() {
        return kind;
    }

    /** @return 冲突时网关的当前版本；未知为 -1 */
    public int currentRevision() {
        return currentRevision;
    }

    /** 这次失败是否可能已经改变了 Gateway。 */
    public boolean outcomeUnknown() {
        return kind == Kind.UNAVAILABLE;
    }
}
