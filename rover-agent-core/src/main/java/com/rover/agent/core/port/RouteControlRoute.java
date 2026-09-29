package com.rover.agent.core.port;

import java.util.List;

/**
 * 一条路由在「可写视图」下的状态：标识、匹配前缀与全部版本目标及其权重。
 *
 * <p>只读调查视图（{@code RouteSnapshot}）刻意不带权重——它回答的是「这条路径打到哪个服务」；
 * 变更执行需要的是「这条路由有哪些版本、各自多少权重、当前版本号是多少」，两者用途不同，
 * 因此各自建模，而不是给只读快照挂上一堆写路径才关心的字段。
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
