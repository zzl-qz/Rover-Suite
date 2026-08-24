package com.rover.gateway.core.filter;

import com.rover.common.spi.filter.Filter;
import com.rover.common.spi.filter.FilterChain;
import com.rover.common.spi.filter.RequestContext;
import com.rover.gateway.core.route.RouteConfig;
import com.rover.gateway.core.proxy.HttpProxyClient;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 15:36:00
 * Description: 内置访问日志过滤器；默认 debug，避免热路径 info 刷盘。可由 filters.accessLog 关掉整条装配。
 */
@Slf4j
public class AccessLogFilter implements Filter {

    /** 尽量靠前执行，保证大部分请求都能打到访问日志。 */
    public static final int ORDER = Integer.MIN_VALUE + 100;

    /** @return 过滤器名称 */
    @Override
    public String getName() {
        return "access-log";
    }

    /** @return 执行顺序，尽量靠前保证可观测性 */
    @Override
    public int getOrder() {
        return ORDER;
    }

    /**
     * 请求结束后打一条完成日志（debug）。
     * 排障时把 logger 调到 DEBUG，或保持默认 INFO 则热路径静默。
     */
    @Override
    public CompletableFuture<Void> doFilter(RequestContext context, FilterChain chain) {
        GatewayRequestContext gatewayContext = (GatewayRequestContext) context;
        return chain.doFilter(context).whenComplete((ignored, err) -> {
            if (!log.isDebugEnabled()) {
                return;
            }
            long costMillis = (System.nanoTime() - gatewayContext.getStartNanos()) / 1_000_000;
            RouteConfig route = gatewayContext.getRoute();
            Integer statusCode = gatewayContext.getStatusCode();
            log.debug(
                    "Gateway request completed: method={}, requestPath={}, routeId={}, businessPrefix={}, "
                            + "targetUrl={}, statusCode={}, costMillis={}",
                    gatewayContext.getRequest().method(),
                    gatewayContext.getRequestPath(),
                    route == null ? "-" : route.getId(),
                    route == null ? "-" : route.getBusinessPrefix(),
                    gatewayContext.getTargetUrl() == null
                            ? "-"
                            : HttpProxyClient.redactTargetUrl(gatewayContext.getTargetUrl()),
                    statusCode == null ? "-" : statusCode,
                    costMillis);
        });
    }
}
