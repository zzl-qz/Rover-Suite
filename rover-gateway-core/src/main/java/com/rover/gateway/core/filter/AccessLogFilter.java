package com.rover.gateway.core.filter;

import com.rover.common.spi.filter.Filter;
import com.rover.common.spi.filter.FilterChain;
import com.rover.common.spi.filter.RequestContext;
import com.rover.gateway.core.route.RouteConfig;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 15:36:00
 * Description: 内置访问日志过滤器，记录完整请求链路耗时
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
     * 先记录请求开始，再放行后续过滤器；
     * 无论下游成功或异常，都在完成回调里打印完整链路日志。
     *
     * @param context 网关请求上下文
     * @param chain     过滤器链，用于继续执行
     * @return 后续过滤器链的异步结果
     */
    @Override
    public CompletableFuture<Void> doFilter(RequestContext context, FilterChain chain) {
        GatewayRequestContext gatewayContext = (GatewayRequestContext) context;
        log.info(
                "Gateway received request: {} {}",
                gatewayContext.getRequest().method(),
                gatewayContext.getRequestPath());
        return chain.doFilter(context).whenComplete((ignored, err) -> {
            // 无论下游抛异常还是正常结束，都补一条完成日志。
            long costMillis = (System.nanoTime() - gatewayContext.getStartNanos()) / 1_000_000;
            RouteConfig route = gatewayContext.getRoute();
            Integer statusCode = gatewayContext.getStatusCode();
            log.info(
                    "Gateway request completed: method={}, requestPath={}, routeId={}, businessPrefix={}, "
                            + "targetUrl={}, statusCode={}, costMillis={}",
                    gatewayContext.getRequest().method(),
                    gatewayContext.getRequestPath(),
                    route == null ? "-" : route.getId(),
                    route == null ? "-" : route.getBusinessPrefix(),
                    gatewayContext.getTargetUrl() == null ? "-" : gatewayContext.getTargetUrl(),
                    statusCode == null ? "-" : statusCode,
                    costMillis);
        });
    }
}
