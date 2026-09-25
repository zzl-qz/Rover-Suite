package com.rover.agent.core.snapshot;

/**
 * Gateway 全局流量与拒绝计数的只读快照。
 *
 * 字段缺失时保留 -1，避免把「没有采集到」误读成「数量为 0」。
 *
 * @param windowRequests     窗口内请求数
 * @param status5xx          窗口内 5xx 数
 * @param noUpstreamRejects  全局累计的无上游拒绝数
 * @param observedAtMillis   取证时刻
 */
public record GatewayMetricSnapshot(long windowRequests, long status5xx, long noUpstreamRejects,
                                    long observedAtMillis) { }