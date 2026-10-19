package com.rover.agent.core.port;

/** 只读快照不可用：数据源不可达、未启用或返回结构无法解析。调用方据此降级为「证据不足」。 */
public class SnapshotUnavailableException extends RuntimeException {
    private final boolean retryable;

    public SnapshotUnavailableException(String message) {
        this(message, null, false);
    }

    public SnapshotUnavailableException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public SnapshotUnavailableException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() { return retryable; }
}
