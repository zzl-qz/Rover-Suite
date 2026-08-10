package com.rover.common.spi;

/**
 * Author: Daylight
 * Created: 2026-08-08 16:53:00
 * Description: 定义网关请求上下文契约，供过滤器读写路径、属性和完成状态
 *
 * 这个接口是什么：一次网关请求在过滤器链中的共享上下文。
 * 核心职责：在过滤器之间传递共享属性(如鉴权后的用户信息)、暴露当前请求路径、
 * 并用完成标记驱动过滤器链的终止判断。
 * 被谁用：所有 Filter 实现类；实现方为 rover-gateway 的请求上下文工厂。
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
