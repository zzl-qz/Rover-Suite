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
 * 按路由 ID、业务前缀、请求路径最长前缀的顺序定位路由与版本目标。
 * 未匹配时返回 null。
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
