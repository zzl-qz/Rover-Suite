package com.rover.agent.core.util;

/**
 * 文本归一化的唯一出处。
 *
 * <p>为什么要有它：同一个「null 当空串」的逻辑原先在十几个类里各写一份私有方法，
 * 每份都叫 {@code text} 或 {@code nullToEmpty}，长得一样却散落各处——读代码的人
 * 每打开一个类都要先确认一遍「这里的空值口径是不是也是这样」，而改口径时要改十几处。
 * 归一化口径属于规则，不属于某个类的内部细节，因此收敛到这里。
 *
 * <p>两个方法有明确分工，混用会悄悄改掉数据：
 * <ul>
 *   <li>{@link #orEmpty}：给「用户/模型/配置给的文本」用，顺手去掉首尾空白；</li>
 *   <li>{@link #raw}：给「从库里读出来的值」用，保留原始内容，不做任何修饰。</li>
 * </ul>
 */
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
