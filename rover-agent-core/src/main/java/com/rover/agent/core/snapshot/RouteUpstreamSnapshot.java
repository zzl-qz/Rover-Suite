package com.rover.agent.core.snapshot;

/**
 * 「路由 × 上游实例」的窗口观测快照：回答「是哪台上游实例返回了 5xx」，以及它属于哪个版本。
 *
 * 与仅按 host:port 汇总的上游指标不同，这里按「路由 + 实例」统计，因此可以定位到具体路由下的具体实例；
 * 数据全部来自 Gateway 的滚动窗口，窗口内没有请求时不得用更早的样本推断当前状态。
 *
 * @param routeId          路由 ID
 * @param hostPort         上游实例地址（host:port）
 * @param group            实例被选中时所处的版本（group）；空串表示无版本 / 默认组。
 *                          灰度场景下它是把错误归因到具体版本的关键维度——同一条路由上
 *                          v1 正常而 v2 报错时，只有带上版本才能说出「是哪个版本在出问题」，
 *                          否则只能得到「某台机器有问题」这种无法指导回滚的结论。
 * @param windowSeconds    统计窗口（秒）
 * @param windowRequests   窗口内该实例被转发的请求数，即本次判断的样本量
 * @param status5xx        窗口内该实例返回的 5xx 数
 * @param connectFail      窗口内该实例连接失败次数
 * @param timeout          窗口内该实例超时次数
 * @param avgMillis        窗口内平均耗时（毫秒）
 * @param p95Millis        窗口内 P95 耗时（毫秒）
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
