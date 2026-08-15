package com.rover.common.spi.filter;

/**
 * Author: Daylight
 * Created: 2026-08-08 16:53:00
 * Description: 网关过滤器 SPI：放行则交给 FilterChain 继续推进，自行收尾则 markCompleted 终止链路
 */
public interface Filter {

    /**
     * 过滤器名称，默认使用全限定类名。
     * 主要用于日志打印，方便排查当前走到了哪个过滤器。
     *
     * @return 过滤器名称
     */
    default String getName() {
        return getClass().getName();
    }

    /**
     * 执行顺序，数值越小越先执行。
     * 例如日志过滤器靠前，路由转发过滤器靠后。
     *
     * @return 排序值，默认 0
     */
    default int getOrder() {
        return 0;
    }

    /**
     * 处理请求。
     * 放行就继续 chain.doFilter；自己收尾就 markCompleted，别再往下走。
     *
     * @param context 请求上下文，可读写共享属性
     * @param chain   过滤器链，用于推进到下一个过滤器
     * @throws Exception 处理过程中允许抛任意异常，由网关统一兜底
     */
    void doFilter(RequestContext context, FilterChain chain) throws Exception;
}
