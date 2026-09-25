package com.rover.agent.core.port;

/** 只读快照不可用：数据源不可达、未启用或返回结构无法解析。调用方据此降级为「证据不足」。 */
public class SnapshotUnavailableException extends RuntimeException {

    public SnapshotUnavailableException(String message) {
        super(message);
    }

    public SnapshotUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}