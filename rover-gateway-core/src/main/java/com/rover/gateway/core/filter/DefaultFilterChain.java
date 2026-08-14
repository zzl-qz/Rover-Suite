package com.rover.gateway.core.filter;

import com.rover.common.spi.filter.Filter;
import com.rover.common.spi.filter.FilterChain;
import com.rover.common.spi.filter.RequestContext;
import java.util.List;

/**
 * Author: Daylight
 * Created: 2026-08-08 16:53:00
 * Description: 按顺序推进过滤器链，直到路由转发或某个过滤器短路
 */
public class DefaultFilterChain implements FilterChain {

    /** 当前请求要执行的过滤器列表，按 order 排好序。 */
    private final List<Filter> filters;

    /** 下一个待执行过滤器的下标，每次 doFilter 推进一格。 */
    private int index;

    /**
     * @param filters 已按 order 排好序的过滤器列表
     */
    public DefaultFilterChain(List<Filter> filters) {
        this.filters = filters;
    }

    /**
     * 执行下一个过滤器。
     * Filter 内部要继续链路就再调 chain.doFilter；自己结束请求就 markCompleted。
     *
     * @param context 当前请求上下文
     * @throws Exception 过滤器抛出的未捕获异常，由 Handler 兜底
     */
    @Override
    public void doFilter(RequestContext context) throws Exception {
        // 前面过滤器已经结束请求，直接停止。
        if (context.isCompleted()) {
            return;
        }
        // 所有过滤器都执行完了，链路结束。
        if (index >= filters.size()) {
            return;
        }

        // 取出当前过滤器，并把下标挪到下一个，避免同一个过滤器重复执行。
        Filter filter = filters.get(index++);
        filter.doFilter(context, this);
    }
}
