package com.rover.common.spi.filter;

/**
 * Author: Daylight
 * Created: 2026-08-08 16:53:00
 * Description: 过滤器链推进契约（Servlet 风格 chain.doFilter）：封装后续过滤器，链路结束自然收尾
 */
public interface FilterChain {

    /**
     * 继续执行下一个过滤器。
     * 如果当前已经是最后一个，链路自然结束；
     * 如果前面已经 markCompleted，后续也不会再执行。
     *
     * @param context 请求上下文
     * @throws Exception 链路内任意过滤器抛出的异常原样上抛
     */
    void doFilter(RequestContext context) throws Exception;
}
