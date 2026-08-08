package com.rover.common.spi;

/**
 * Author: Daylight
 * Created: 2026-08-08 16:53:00
 * Description: 定义网关请求上下文契约，供过滤器读写路径、属性和完成状态
 */
public interface RequestContext {

    /**
     * 读取过滤器共享属性。
     * 例如前面鉴权 Filter 放进去的用户信息，后面 Filter 还能取到。
     */
    Object getAttribute(String key);

    /**
     * 写入过滤器共享属性。
     */
    void setAttribute(String key, Object value);

    /**
     * 当前请求路径，不含 query。
     * 例如 /api/uu/admin/list
     */
    String getRequestPath();

    /**
     * 响应是否已经由某个过滤器写回客户端。
     * 为 true 时，过滤器链应停止继续转发。
     */
    boolean isCompleted();

    /**
     * 标记请求已完成。
     * 通常在过滤器自己写完响应后调用。
     */
    void markCompleted();
}
