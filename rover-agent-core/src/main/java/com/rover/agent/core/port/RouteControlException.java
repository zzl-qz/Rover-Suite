package com.rover.agent.core.port;

/**
 * 一次路由写操作失败，并且<b>失败的性质决定了接下来该怎么做</b>。
 *
 * <p>四类失败的处理方式完全不同，因此不能在调用方被压成同一个「执行失败」：
 *
 * <ul>
 *   <li>{@link Kind#CONFLICT}：别人先改了，本次没有生效。重读版本即可，不需要补偿。</li>
 *   <li>{@link Kind#REJECTED}：请求本身不合法（权重越界、目标不存在），本次没有生效，改参数才能重来。</li>
 *   <li>{@link Kind#FAILED}：网关认了请求但落盘失败，本次没有生效；用同一个 operationId 重试是安全的。</li>
 *   <li>{@link Kind#UNAVAILABLE}：<b>没拿到响应</b>。可能生效了也可能没有，只能用原 operationId 回查，
 *       绝不能换一个号重新提交——那正是「同一个变更被执行两次」的来源。</li>
 * </ul>
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

    /** 这次失败是否可能已经改变了 Gateway（只有「没拿到响应」有这种可能）。 */
    public boolean outcomeUnknown() {
        return kind == Kind.UNAVAILABLE;
    }
}
