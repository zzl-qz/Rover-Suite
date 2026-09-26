package com.rover.admin.agent.adapter;

import java.util.Objects;

/** 管理口 JSON 字段的宽松取值：缺失与空白统一收敛为空串或 0，避免把「没有字段」当成异常。 */
final class AdminValues {

    private AdminValues() { }

    static String text(Object value) {
        return Objects.toString(value, "").trim();
    }

    static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = text(value);
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = text(value);
        if (text.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    static double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String text = text(value);
        if (text.isEmpty()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    /** 缺省值语义：字段缺失或不是 true 时为 false，不把「没有字段」当成异常。 */
    static boolean boolValue(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(text(value));
    }
}