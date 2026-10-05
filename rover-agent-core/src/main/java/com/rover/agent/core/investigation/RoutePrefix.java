package com.rover.agent.core.investigation;

/** 统一路由前缀匹配：/ 匹配所有路径，其余按相等或 prefix + / 匹配。 */
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
