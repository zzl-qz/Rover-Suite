package com.rover.agent.core.investigation;

import com.rover.agent.core.snapshot.RouteSnapshot;
import com.rover.agent.core.util.Texts;
import java.util.Comparator;
import java.util.List;

/** 路径到路由的匹配口径：最长业务前缀优先，{@code /} 匹配所有路径。 */
public final class RouteMatcher {

    private RouteMatcher() { }

    /** 返回匹配该路径的路由；没有匹配项时返回 {@code null}。 */
    public static RouteSnapshot match(List<RouteSnapshot> routes, String path) {
        if (routes == null || path == null) {
            return null;
        }
        return routes.stream()
                .filter(route -> matches(route, path))
                .max(Comparator.comparingInt(route -> Texts.orEmpty(route.businessPrefix()).length()))
                .orElse(null);
    }

    private static boolean matches(RouteSnapshot route, String path) {
        return RoutePrefix.matches(route.businessPrefix(), path);
    }

}