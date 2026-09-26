package com.rover.agent.core.port;

import com.rover.agent.core.snapshot.GatewayMetricSnapshot;
import com.rover.agent.core.snapshot.RouteUpstreamSnapshot;
import java.util.List;

/** 只读读取 Gateway 指标。 */
public interface MetricReadPort {

    /**
     * 读取 Gateway 最近 windowSeconds 秒的全局流量与拒绝计数快照。
     * 指标未启用或数据不可用时抛 {@link SnapshotUnavailableException}。
     */
    GatewayMetricSnapshot gatewayWindow(int windowSeconds);

    /**
     * 读取指定路由下「路由 × 上游实例」的窗口观测：请求数、状态码、错误率与延迟。
     *
     * 这是把「哪台实例返回了 5xx」落到具体实例的唯一数据源；路由不存在或窗口内没有转发记录时返回空列表，
     * 而指标未启用、端点不可达等取数失败抛 {@link SnapshotUnavailableException}——「没有样本」与
     * 「取不到数据」必须分开，否则会拿旧样本或空数据当成结论。
     *
     * 默认实现明确表示「该接入不提供这个维度」，并同样按取数失败处理（记为判断边界），
     * 绝不用空列表冒充「窗口内没有样本」。
     */
    default List<RouteUpstreamSnapshot> routeUpstreams(String routeId, int windowSeconds) {
        throw new SnapshotUnavailableException("当前指标接入未提供按上游实例的窗口观测");
    }
}