package com.rover.agent.runtime.action;

import com.rover.agent.core.investigation.RoutePrefix;
import com.rover.agent.core.port.RouteControlRoute;
import com.rover.agent.core.port.RouteControlState;
import com.rover.agent.core.port.RouteControlTarget;
import com.rover.agent.core.util.Texts;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 把「人话里的那条路由、那个版本」落到可写视图上的具体路由与目标。
 *
 * <p>匹配分三档：先认路由 ID，再认业务前缀，最后当成请求路径按最长前缀匹配。
 * 三档的顺序很重要——用户说 {@code /api/order} 时，它既可能是前缀也可能是一条路径，
 * 而两条路由同时匹配一个路径时，网关真正转发用的是最长前缀，因此这里也必须按最长前缀取，
 * 否则会出现「给 A 加权重，实际分流的是 B」。
 *
 * <p>匹配不到时返回 {@code null} 而不抛异常：调用方要的是一句能给人看的说明，
 * 「找不到」是正常业务结果，不是程序错误。
 */
final class RouteLocator {

    private RouteLocator() { }

    /** 按 ID → 业务前缀 → 请求路径（最长前缀优先）找一条路由。 */
    static RouteControlRoute route(RouteControlState state, String key, String fallbackPrefix) {
        List<RouteControlRoute> routes = state.routes();
        String wanted = Texts.orEmpty(key);
        if (!wanted.isBlank()) {
            RouteControlRoute byId = routes.stream()
                    .filter(route -> wanted.equals(route.routeId()))
                    .findFirst().orElse(null);
            if (byId != null) {
                return byId;
            }
            RouteControlRoute byPrefix = routes.stream()
                    .filter(route -> wanted.equals(route.businessPrefix()))
                    .findFirst().orElse(null);
            if (byPrefix != null) {
                return byPrefix;
            }
            RouteControlRoute byPath = routes.stream()
                    .filter(route -> RoutePrefix.matches(route.businessPrefix(), wanted))
                    .max(Comparator.comparingInt(route -> Texts.orEmpty(route.businessPrefix()).length()))
                    .orElse(null);
            if (byPath != null) {
                return byPath;
            }
        }
        String prefix = Texts.orEmpty(fallbackPrefix);
        if (!prefix.isBlank()) {
            return routes.stream().filter(route -> prefix.equals(route.businessPrefix())).findFirst().orElse(null);
        }
        return null;
    }

    /**
     * 找一个版本目标：先按「服务名 + 分组」精确匹配，服务名留空时只按分组匹配（提议阶段还不知道服务名）。
     *
     * @return 匹配到的目标；没有则返回 {@code null}
     */
    static RouteControlTarget target(RouteControlRoute route, String serviceName, String group) {
        String wantedGroup = Texts.orEmpty(group);
        String wantedService = Texts.orEmpty(serviceName);
        for (RouteControlTarget target : route.targets()) {
            if (!wantedGroup.equals(target.group())) {
                continue;
            }
            if (wantedService.isBlank() || wantedService.equals(target.serviceName())) {
                return target;
            }
        }
        return null;
    }

    /** 现有版本目标的清单，用于「没有这个版本」时把可选值一次说清楚。 */
    static String describeTargets(RouteControlRoute route) {
        if (route.targets().isEmpty()) {
            return route.staticUpstream() ? "静态上游地址 " + route.targetUrl() : "未配置版本目标";
        }
        return route.targets().stream().map(target -> target.label() + "=" + target.weight())
                .collect(Collectors.joining("、"));
    }

}
