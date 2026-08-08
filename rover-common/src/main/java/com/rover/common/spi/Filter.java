package com.rover.common.spi;

/**
 * Author: Daylight
 * Created: 2026-08-08 16:53:00
 * Description: 定义网关过滤器执行契约，支持 plugins 目录外挂二开
 */
public interface Filter {

    /**
     * 过滤器名称，默认使用全限定类名。
     * 主要用于日志打印，方便排查当前走到了哪个过滤器。
     */
    default String getName() {
        return getClass().getName();
    }

    /**
     * 执行顺序，数值越小越先执行。
     * 例如日志过滤器靠前，路由转发过滤器靠后。
     */
    default int getOrder() {
        return 0;
    }

    /**
     * 处理请求。
     * 放行就继续 chain.doFilter；自己收尾就 markCompleted，别再往下走。
     */
    void doFilter(RequestContext context, FilterChain chain) throws Exception;
}
