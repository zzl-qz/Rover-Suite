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
}