package com.rover.gateway.core.route;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Author: Daylight
 * Created: 2026-08-08 14:22:00
 * Description: 按业务前缀匹配 Gateway 路由，长前缀优先
 */
public class RouteMatcher {

    private final List<RouteConfig> routes;

    public RouteMatcher() {
        this(List.of());
    }

    public RouteMatcher(List<RouteConfig> routes) {
        this.routes = new ArrayList<>(routes);
        // 更长的路径优先，避免 /api/** 抢在 /api/user/** 前面命中。
        this.routes.sort(Comparator.comparingInt(this::pathLength).reversed());
    }

    /**
     * 根据请求路径匹配业务前缀，业务前缀越长优先级越高。
     */
    public RouteConfig match(String path) {
        for (RouteConfig route : routes) {
            if (matches(route.getBusinessPrefix(), path)) {
                return route;
            }
        }
        return null;
    }

    private boolean matches(String pattern, String path) {
        if (pattern == null || pattern.isBlank() || path == null || path.isBlank()) {
            return false;
        }
        return path.equals(pattern) || path.startsWith(pattern + "/");
    }

    private int pathLength(RouteConfig route) {
        String businessPrefix = route.getBusinessPrefix();
        if (businessPrefix == null) {
            return 0;
        }
        return businessPrefix.length();
    }
}
