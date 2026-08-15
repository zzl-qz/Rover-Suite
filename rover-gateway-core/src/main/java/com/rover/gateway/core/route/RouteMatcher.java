package com.rover.gateway.core.route;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Author: Daylight
 * Created: 2026-08-08 13:06:00
 * Description: 路由表匹配引擎：按业务前缀匹配请求路径，长前缀优先
 */
public class RouteMatcher {

    /** 当前生效的路由列表，长前缀排在前面。 */
    private final List<RouteConfig> routes;

    /** 空路由表构造。 */
    public RouteMatcher() {
        this(List.of());
    }

    /** 按 businessPrefix 长度降序构造路由匹配器，长前缀优先命中。 */
    public RouteMatcher(List<RouteConfig> routes) {
        this.routes = new ArrayList<>(routes);
        // 更长的路径优先，避免 /api/** 抢在 /api/user/** 前面命中。
        this.routes.sort(Comparator.comparingInt(this::pathLength).reversed());
    }

    /**
     * 根据请求路径匹配业务前缀，业务前缀越长优先级越高。
     *
     * @param path 请求路径（不含 query）
     * @return 命中的路由；无匹配时 null
     */
    public RouteConfig match(String path) {
        for (RouteConfig route : routes) {
            if (matches(route.getBusinessPrefix(), path)) {
                return route;
            }
        }
        return null;
    }

    /** 返回当前路由表只读副本，供管理口展示。 */
    public List<RouteConfig> listRoutes() {
        return List.copyOf(routes);
    }

    /** 判断 path 是否等于 pattern 或以 pattern/ 开头。 */
    private boolean matches(String pattern, String path) {
        if (pattern == null || pattern.isBlank() || path == null || path.isBlank()) {
            return false;
        }
        return path.equals(pattern) || path.startsWith(pattern + "/");
    }

    /** 取 businessPrefix 长度，用于排序。 */
    private int pathLength(RouteConfig route) {
        String businessPrefix = route.getBusinessPrefix();
        if (businessPrefix == null) {
            return 0;
        }
        return businessPrefix.length();
    }
}
