package com.rover.agent.core.investigation;

/**
 * 路由前缀的匹配口径：{@code /} 匹配所有路径，其余前缀按「相等」或「前缀 + /」开头匹配。
 *
 * <p>只读视图（{@link RouteMatcher}）与变更视图（执行前的目标定位）必须用同一套规则：
 * 两处各写一份的话，会出现「提议时匹配到路由 A、执行时匹配到路由 B」这种最不该发生的事。
 */
public final class RoutePrefix {

    private RoutePrefix() { }

    /** 前缀是否命中该路径。空前缀不匹配任何路径。 */
    public static boolean matches(String prefix, String path) {
        String text = prefix == null ? "" : prefix.trim();
        if (text.isBlank() || path == null) {
            return false;
        }
        return "/".equals(text) || path.equals(text) || path.startsWith(text + "/");
    }
}
