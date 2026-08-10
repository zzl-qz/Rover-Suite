package com.rover.common.util;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 提供对象与 JSON 字符串互转工具
 *
 * 这个类是什么：JSON 序列化/反序列化静态门面工具。
 * 核心职责：统一遮住底层 JSON 库(Jackson/Gson 等)细节，为各模块提供
 * 对象 -> JSON、JSON -> 对象 两个稳定入口，便于日后切换实现。
 * 被谁用：管理端接口、配置读取、HTTP 网关侧等需要 JSON 互转的代码。
 */
public final class JsonUtil {

    /** 工具类不允许实例化 */
    private JsonUtil() {
    }

    /**
     * 对象序列化为 JSON 字符串(实现待补全)。
     *
     * @param obj 待序列化对象
     * @return JSON 字符串
     * @throws UnsupportedOperationException 尚未实现
     */
    public static String toJson(Object obj) {
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * JSON 字符串反序列化为指定类型(实现待补全)。
     *
     * @param json  JSON 字符串
     * @param clazz 目标类型
     * @return 反序列化得到的对象
     * @throws UnsupportedOperationException 尚未实现
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        throw new UnsupportedOperationException("TODO");
    }
}
