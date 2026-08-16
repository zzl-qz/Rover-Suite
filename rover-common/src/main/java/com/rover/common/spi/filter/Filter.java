package com.rover.common.spi.filter;

import java.util.concurrent.CompletableFuture;

/**
 * Author: Daylight
 * Created: 2026-08-08 16:53:00
 * Description: 网关过滤器 SPI：放行则交给 FilterChain 继续推进，自行收尾则 markCompleted 终止链路。
 * 采用异步契约：doFilter 返回 CompletableFuture，非阻塞过滤器不占用业务线程等待上游。
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
     * 同步阶段可抛异常（由 FilterChain 统一转成 failedFuture）；异步阶段通过返回的 Future 传递结果/异常。
     *
     * @param context 请求上下文，可读写共享属性
     * @param chain   过滤器链，用于推进到下一个过滤器
     * @return 处理完成的异步结果，不能返回 null
     * @throws Exception 同步阶段允许抛任意异常，由网关统一兜底
     */
    CompletableFuture<Void> doFilter(RequestContext context, FilterChain chain) throws Exception;
}
