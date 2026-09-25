package com.rover.agent.core.port;

import com.rover.agent.core.snapshot.GatewayMetricSnapshot;

/** 只读读取 Gateway 指标。 */
public interface MetricReadPort {

    /**
     * 读取 Gateway 最近 windowSeconds 秒的全局流量与拒绝计数快照。
     * 指标未启用或数据不可用时抛 {@link SnapshotUnavailableException}。
     */
    GatewayMetricSnapshot gatewayWindow(int windowSeconds);
}