/**
 * 作者：Daylight
 * 创建时间：2026-08-08 10:34:00
 * 描述：定义网关过滤器执行契约
 */
package com.rover.common.spi;

public interface Filter {

    void doFilter(RequestContext context, FilterChain chain);
}
