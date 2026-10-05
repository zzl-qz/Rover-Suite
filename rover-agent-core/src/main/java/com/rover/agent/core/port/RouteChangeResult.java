package com.rover.agent.core.port;

/**
 * 路由写请求结果：APPLIED 为首次生效，REPLAYED 为幂等重放。
 * 目标状态仍须通过回读验证。
 *
 * @param status      {@code APPLIED} / {@code REPLAYED}
 * @param revision    生效后的路由版本
 * @param operationId 产生该版本的幂等号
 * @param message     网关给的人类可读说明
 */
public record RouteChangeResult(String status, int revision, String operationId, String message) {

    public RouteChangeResult {
        status = status == null ? "" : status.trim();
        operationId = operationId == null ? "" : operationId.trim();
        message = message == null ? "" : message;
    }

    /** 本次请求是否真的改动了 Gateway（重放不算再次改动，但目标状态已达成）。 */
    public boolean applied() {
        return "APPLIED".equalsIgnoreCase(status) || "REPLAYED".equalsIgnoreCase(status);
    }

    /** 是否是幂等重放：说明之前那一次已经生效过。 */
    public boolean replayed() {
        return "REPLAYED".equalsIgnoreCase(status);
    }
}
