package com.rover.common.util;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 提供字符串判空和空值规整工具
 *
 * 这个类是什么：字符串判空与规整的静态工具。
 * 核心职责：提供语义明确的判空与 trim 兜底，替代散落的 str == null ||
 * "".equals(str) 写法。
 * 被谁用：各模块的入参校验、配置取值、请求字段处理。
 */
public final class StringUtil {

    /** 工具类不允许实例化 */
    private StringUtil() {
    }

    /**
     * 判断字符串是否为空(实现待补全)。
     *
     * @param str 待判断字符串
     * @return true 表示 null 或空串
     * @throws UnsupportedOperationException 尚未实现
     */
    public static boolean isEmpty(String str) {
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * 去除首尾空白并兜底空值(实现待补全)。
     *
     * @param str 待规整字符串
     * @return trim 后的字符串；null 时返回空串
     * @throws UnsupportedOperationException 尚未实现
     */
    public static String trimToEmpty(String str) {
        throw new UnsupportedOperationException("TODO");
    }
}
