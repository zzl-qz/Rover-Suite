package com.rover.agent.core.port;

/**
 * 一次路由写请求的结果。
 *
 * <p>{@code status} 是网关的原始记账口径：{@code APPLIED} 表示这次请求真的生效了，
 * {@code REPLAYED} 表示同一个 operationId 之前已经生效过、这次没有再次改动。
 * 两者对调用方都是「目标状态已经达成」，但保留区别是因为它们对应的日志与解释完全不同。
 *
 * <p>注意：这里返回成功只代表<b>请求被受理并生效</b>，不代表调用方要的目标已经达成——
 * 目标达成与否必须靠自己回读路由确认（网关可能同时被别的手改过）。
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
