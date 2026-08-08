package com.rover.common.spi;

/**
 * Author: Daylight
 * Created: 2026-08-08 16:53:00
 * Description: 定义网关过滤器链推进契约，供 Filter 继续执行下一环
 */
public interface FilterChain {

    /**
     * 继续执行下一个过滤器。
     * 如果当前已经是最后一个，链路自然结束；
     * 如果前面已经 markCompleted，后续也不会再执行。
     */
    void doFilter(RequestContext context) throws Exception;
}
