package com.rover.gateway.core.filter;

import com.rover.common.spi.Filter;
import com.rover.common.spi.FilterChain;
import com.rover.common.spi.RequestContext;
import com.rover.gateway.core.route.RouteConfig;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 16:53:00
 * Description: 内置访问日志过滤器，记录完整请求链路耗时
 */
@Slf4j
public class AccessLogFilter implements Filter {

    /** 尽量靠前执行，保证大部分请求都能打到访问日志。 */
    public static final int ORDER = Integer.MIN_VALUE + 100;

    @Override
    public String getName() {
        return "access-log";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    /**
     * 先记录请求开始，再放行后续过滤器；
     * 不管后面成功还是失败，finally 里都会打印完整链路日志。
     */
    @Override
    public void doFilter(RequestContext context, FilterChain chain) throws Exception {
        GatewayRequestContext gatewayContext = (GatewayRequestContext) context;
        log.info(
                "Gateway received request: {} {}",
                gatewayContext.getRequest().method(),
                gatewayContext.getRequestPath());
        try {
            // 放行到下一个过滤器（可能是用户插件，也可能是路由转发）。
            chain.doFilter(context);
        } finally {
            // 无论后面抛异常还是正常结束，都补一条完成日志。
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
        }
    }
}
