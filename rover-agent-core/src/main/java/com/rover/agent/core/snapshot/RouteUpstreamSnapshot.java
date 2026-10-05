package com.rover.agent.core.snapshot;

/**
 * 按路由和上游实例统计的窗口观测；无当前样本时不使用历史数据判定。
 *
 * @param routeId 路由 ID
 * @param hostPort 上游实例地址（host:port）
 * @param group 实例被选中时的版本分组；空串表示默认组或无版本
 * @param windowSeconds 统计窗口秒数
 * @param windowRequests 窗口内转发请求数，也是本次判断的样本量
 * @param status5xx 窗口内 5xx 数
 * @param connectFail 窗口内连接失败次数
 * @param timeout 窗口内超时次数
 * @param avgMillis 窗口内平均耗时（毫秒）
 * @param p95Millis 窗口内 P95 耗时（毫秒）
 * @param observedAtMillis 取证时刻
 */
public record RouteUpstreamSnapshot(String routeId, String hostPort, String group, int windowSeconds,
                                    long windowRequests, long status5xx, long connectFail, long timeout,
                                    double avgMillis, long p95Millis, long observedAtMillis) {

    /** 无版本 / 默认组的取值，与网关 instanceGroups 的空串口径一致。 */
    public static final String NO_GROUP = "";

    /** 缺失的版本一律归一成空串：让下游只判「有没有版本」，不必处理 null。 */
    public RouteUpstreamSnapshot {
        group = group == null ? NO_GROUP : group.trim();
    }

    /** 该实例是否属于某个明确版本（区别于无版本的默认组）。 */
    public boolean hasGroup() {
        return !group.isEmpty();
    }
}
