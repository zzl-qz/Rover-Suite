package com.rover.agent.core.util;

/** 文本空值处理：orEmpty 去掉首尾空白，raw 保留原始内容；null 均转为空串。 */
public final class Texts {

    private Texts() {
    }

    /** null → 空串，否则去掉首尾空白。 */
    public static String orEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /** null → 空串，但保留原始空白：读库回显不该被悄悄 trim。 */
    public static String raw(String value) {
        return value == null ? "" : value;
    }

    /** 是否为空（null 或去掉空白后没有内容）。 */
    public static boolean isBlank(String value) {
        return orEmpty(value).isEmpty();
    }

    /** 是否有内容：判断「用户到底说了没有」时用这个，不要让 null 检查散落在业务里。 */
    public static boolean hasText(String value) {
        return !isBlank(value);
    }

    /** 取第一个有内容的文本：没有时返回空串，而不是 null。 */
    public static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (hasText(value)) {
                return orEmpty(value);
            }
        }
        return "";
    }
}
