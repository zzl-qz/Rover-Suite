package com.rover.common.spi.filter;

/**
 * Author: Daylight
 * Created: 2026-08-08 16:53:00
 * Description: 网关请求上下文契约：过滤器间传递共享属性、暴露请求路径，并用完成标记驱动链路终止
 */
public interface RequestContext {

    /**
     * 读取过滤器共享属性。
     * 例如前面鉴权 Filter 放进去的用户信息，后面 Filter 还能取到。
     *
     * @param key 属性名
     * @return 属性值，不存在时为 null
     */
    Object getAttribute(String key);

    /**
     * 写入过滤器共享属性。
     *
     * @param key   属性名
     * @param value 属性值
     */
    void setAttribute(String key, Object value);

    /**
     * 当前请求路径，不含 query。
     * 例如 /api/uu/admin/list
     *
     * @return 原始请求路径
     */
    String getRequestPath();

    /** 读取请求头；未找到时返回 null。 */
    default String requestHeader(String name) {
        return null;
    }

    /** 直接拒绝请求并写回文本响应。 */
    default void reject(int status, String body) {
        throw new UnsupportedOperationException("request rejection is not supported");
    }

    /**
     * 响应是否已经由某个过滤器写回客户端。
     * 为 true 时，过滤器链应停止继续转发。
     *
     * @return true 表示请求已完成
     */
    boolean isCompleted();

    /**
     * 标记请求已完成。
     * 通常在过滤器自己写完响应后调用。
     */
    void markCompleted();
}
