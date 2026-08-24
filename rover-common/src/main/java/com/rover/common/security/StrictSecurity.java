package com.rover.common.security;

/**
 * Author: Daylight
 * Description: 严格安全模式。本地默认关闭（空 token 仅 WARN）；
 * 生产用环境变量或系统属性打开后，空 token 直接启动失败。
 *
 * 开启方式（任一即可）：
 * - 环境变量 ROVER_STRICT_SECURITY=true
 * - JVM -Drover.strictSecurity=true
 */
public final class StrictSecurity {

    public static final String ENV_NAME = "ROVER_STRICT_SECURITY";
    public static final String PROPERTY_NAME = "rover.strictSecurity";

    private StrictSecurity() {
    }

    /** 是否开启严格安全（空 token 拒绝启动）。 */
    public static boolean enabled() {
        String property = System.getProperty(PROPERTY_NAME);
        if (property != null && !property.isBlank()) {
            return parseTruthy(property);
        }
        String env = System.getenv(ENV_NAME);
        return env != null && parseTruthy(env);
    }

    private static boolean parseTruthy(String raw) {
        String value = raw.trim();
        return "1".equals(value)
                || "true".equalsIgnoreCase(value)
                || "yes".equalsIgnoreCase(value)
                || "on".equalsIgnoreCase(value);
    }
}
