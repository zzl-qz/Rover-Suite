package com.rover.gateway.core.filter;

import com.rover.common.spi.filter.Filter;
import com.rover.common.spi.filter.FilterChain;
import com.rover.common.spi.filter.RequestContext;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Author: Daylight
 * Created: 2026-08-08 16:53:00
 * Description: 按顺序推进过滤器链（异步），直到路由转发或某个过滤器短路。
 * 同步阶段抛出的异常统一转成 failedFuture，避免污染调用方。
 */
public class DefaultFilterChain implements FilterChain {

    /** 当前请求要执行的过滤器列表，按 order 排好序。 */
    private final List<Filter> filters;

    /** 下一个待执行过滤器的下标，每次 doFilter 推进一格。 */
    private int index;

    /** 构造：传入已按 order 排好序的过滤器列表。 */
    public DefaultFilterChain(List<Filter> filters) {
        this.filters = filters;
    }

    /** 执行下一个过滤器；Filter 内部调 chain.doFilter 续链，或 markCompleted 结束请求。 */
    @Override
    public CompletableFuture<Void> doFilter(RequestContext context) {
        if (context.isCompleted()) {
            return CompletableFuture.completedFuture(null);
        }
        if (index >= filters.size()) {
            return CompletableFuture.completedFuture(null);
        }

        // 取出当前过滤器并把下标后移，避免同一过滤器重复执行。
        Filter filter = filters.get(index++);
        try {
            return filter.doFilter(context, this);
        } catch (Exception err) {
            return CompletableFuture.failedFuture(err);
        }
    }
}
