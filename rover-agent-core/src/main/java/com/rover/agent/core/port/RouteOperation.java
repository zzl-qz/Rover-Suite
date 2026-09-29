package com.rover.agent.core.port;

/**
 * 按 operationId 回查到的操作结果：回答「我超时的那次写，到底执行了没有」。
 *
 * @param operationId     查询用的幂等号
 * @param status          操作终态
 * @param revision        该操作对应的版本；冲突时为网关的当前版本，没有记录时为 0
 * @param message         网关给的人类可读说明
 * @param currentRevision 查询时刻网关的当前版本
 */
public record RouteOperation(String operationId, RouteOperationStatus status, int revision, String message,
                             int currentRevision) {

    public RouteOperation {
        message = message == null ? "" : message;
    }

    /** 是否确认这次操作真的改动了 Gateway。 */
    public boolean applied() {
        return status.applied();
    }
}
