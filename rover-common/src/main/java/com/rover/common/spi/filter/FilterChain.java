package com.rover.common.spi.filter;

import java.util.concurrent.CompletableFuture;

/**
 * Author: Daylight
 * Created: 2026-08-08 16:53:00
 * Description: 过滤器链推进契约（异步）：封装后续过滤器，链路结束自然收尾。
 * doFilter 返回 CompletableFuture，异常统一放进返回的 Future 而非同步上抛。
 */
public interface FilterChain {

    /**
     * 继续执行下一个过滤器。
     * 如果当前已经是最后一个，链路自然结束；
     * 如果前面已经 markCompleted，后续也不会再执行。
     *
     * @param context 请求上下文
     * @return 后续过滤器链全部执行完毕的异步结果（异常通过 Future 传递）
     */
    CompletableFuture<Void> doFilter(RequestContext context);
}
