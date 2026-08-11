package com.rover.gateway.core.route;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Author: Daylight
 * Created: 2026-08-08 14:22:00
 * Description: 按业务前缀匹配 Gateway 路由，长前缀优先
 *
 * 这个类是什么：路由表匹配引擎，把请求路径映射到 RouteConfig。
 * 核心职责：构造时按 businessPrefix 长度降序排序；match 时精确或前缀匹配；
 * listRoutes 供管理口只读展示。
 * 被谁用：RouteAndProxyFilter 查路由；GatewayRuntime 热更新时替换实例。
 */
public class RouteMatcher {

    /** 当前生效的路由列表，长前缀排在前面。 */
    private final List<RouteConfig> routes;

    /** 空路由表构造。 */
    public RouteMatcher() {
        this(List.of());
    }

    /**
     * @param routes 路由列表，构造时会按 businessPrefix 长度降序排序
     */
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

    /**
     * 返回当前路由表副本，供管理口只读展示。
     *
     * @return 不可变路由列表
     */
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
