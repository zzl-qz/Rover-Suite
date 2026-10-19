package com.rover.gateway.core.metrics;

import com.rover.common.spi.filter.Filter;
import com.rover.common.spi.filter.FilterChain;
import com.rover.common.spi.filter.RequestContext;
import com.rover.gateway.core.filter.GatewayRequestContext;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Description: 指标采集过滤器：请求结束时把状态码/耗时/路由维度累加进 MetricsRegistry。
 * 采集逻辑全部包在 try/catch 内，任何异常只记日志，绝不影响请求转发。
 */
@Slf4j
public class MetricsFilter implements Filter {

    /** 紧跟访问日志之后，保证绝大多数请求都能被统计。 */
    public static final int ORDER = Integer.MIN_VALUE + 200;

    private final MetricsRegistry registry;

    public MetricsFilter(MetricsRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String getName() {
        return "metrics";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    /**
     * 放行后续过滤器，完成回调里聚合本次请求指标；
     * 无论下游成功、短路还是抛异常，都恰好记录一次。
     */
    @Override
    public CompletableFuture<Void> doFilter(RequestContext context, FilterChain chain) {
        GatewayRequestContext gatewayContext = (GatewayRequestContext) context;
        if (!registry.getSettings().isEnabled()) {
            return chain.doFilter(context);
        }
        return chain.doFilter(context).whenComplete((ignored, err) -> {
            // 异常或未写响应的链由 handler 完成兜底后再采集，不能把预置的 200 当最终结果。
            if (err != null || !gatewayContext.isCompleted()) { return; }
            try {
                registry.recordCompleted(gatewayContext);
            } catch (Exception recordErr) {
                log.warn("Metrics record failed, ignore to protect request path", recordErr);
            }
        });
    }
}
