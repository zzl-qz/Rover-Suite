package com.rover.common.config;

import java.util.List;

/** 运行时配置通用值，避免布尔值和脱敏占位符散落。 */
public final class ConfigValues {

    private ConfigValues() {
    }

    public static final String TRUE = "true";
    public static final String FALSE = "false";
    public static final String MASKED = "******";
    public static final List<String> BOOLEAN_OPTIONS = List.of(TRUE, FALSE);

    public static boolean isBoolean(String value) {
        return TRUE.equalsIgnoreCase(value) || FALSE.equalsIgnoreCase(value);
    }

    public static String normalizeBoolean(String value) {
        return Boolean.toString(Boolean.parseBoolean(value));
    }
}
