package com.rover.agent.core.port;

import java.util.List;

/**
 * 可写路由视图，包含匹配前缀和版本目标权重。
 *
 * @param routeId        Gateway 给出的路由 ID
 * @param businessPrefix 业务前缀（路径匹配用）
 * @param targetUrl      静态上游地址；动态服务路由为空
 * @param targets        版本目标；静态路由或未配置版本目标时为空
 */
public record RouteControlRoute(String routeId, String businessPrefix, String targetUrl,
                                List<RouteControlTarget> targets) {

    public RouteControlRoute {
        routeId = routeId == null ? "" : routeId.trim();
        businessPrefix = businessPrefix == null ? "" : businessPrefix.trim();
        targetUrl = targetUrl == null ? "" : targetUrl.trim();
        targets = targets == null ? List.of() : List.copyOf(targets);
    }

    /** 是否是静态上游路由（没有版本目标，权重调整对它没有意义）。 */
    public boolean staticUpstream() {
        return targets.isEmpty() && !targetUrl.isBlank();
    }

    /** 展示用名称：优先业务前缀（用户认这个），其次路由 ID。 */
    public String display() {
        return businessPrefix.isBlank() ? routeId : businessPrefix;
    }
}
