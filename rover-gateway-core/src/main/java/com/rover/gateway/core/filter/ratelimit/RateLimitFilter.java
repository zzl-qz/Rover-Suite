package com.rover.gateway.core.filter.ratelimit;

import com.rover.common.spi.filter.Filter;
import com.rover.common.spi.filter.FilterChain;
import com.rover.common.spi.filter.RequestContext;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;

/**
 * Gateway 内置本地限流：按实例运行，不访问外部存储，默认不装配。
 */
@Slf4j
public class RateLimitFilter implements Filter {

    /** 尽量在用户外挂 Filter 前拒绝超额请求。 */
    public static final int ORDER = Integer.MIN_VALUE + 300;

    private final RateLimitSettings settings;
    private final RateLimiter limiter;

    public RateLimitFilter(RateLimitSettings settings) {
        this.settings = settings == null ? new RateLimitSettings() : settings;
        this.limiter = createLimiter(this.settings);
        log.info("Built-in rate limit enabled: algorithm={}, key={}",
                this.settings.getAlgorithm(), this.settings.getKey());
    }

    @Override
    public String getName() {
        return "rate-limit";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public CompletableFuture<Void> doFilter(RequestContext context, FilterChain chain) {
        if (!limiter.tryAcquire(resolveKey(context))) {
            context.reject(429, "Too Many Requests");
            return CompletableFuture.completedFuture(null);
        }
        return chain.doFilter(context);
    }

    private String resolveKey(RequestContext context) {
        return RateLimitSettings.GLOBAL.equals(settings.getKey())
                ? "__global__"
                : context.getRequestPath();
    }

    private static RateLimiter createLimiter(RateLimitSettings settings) {
        return switch (settings.getAlgorithm()) {
            case RateLimitSettings.TOKEN_BUCKET -> new TokenBucketRateLimiter(
                    settings.getPermitsPerSecond(), settings.getBurst());
            case RateLimitSettings.SLIDING_WINDOW -> new SlidingWindowRateLimiter(
                    settings.getLimit(), settings.getWindowSeconds());
            default -> throw new IllegalArgumentException(
                    "不支持的限流算法: " + settings.getAlgorithm()
                            + "，可选: token_bucket/sliding_window");
        };
    }
}
