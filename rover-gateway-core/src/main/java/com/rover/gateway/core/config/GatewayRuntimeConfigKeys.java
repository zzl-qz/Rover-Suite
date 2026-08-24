package com.rover.gateway.core.config;

import java.util.Set;

/** Gateway 支持热更新的配置键。 */
public final class GatewayRuntimeConfigKeys {

    private GatewayRuntimeConfigKeys() {
    }

    public static final String LOAD_BALANCE_STRATEGY = "gateway.loadbalance.strategy";
    public static final String REQUEST_TIMEOUT_MILLIS = "gateway.request.timeoutMillis";
    public static final String METRICS_ENABLED = "gateway.metrics.enabled";
    public static final String METRICS_WINDOW_SECONDS = "gateway.metrics.windowSeconds";
    public static final String FILTER_ENABLED = "gateway.filter.enabled";
    public static final String RATE_LIMIT_ENABLED = "gateway.rateLimit.enabled";
    public static final String RATE_LIMIT_ALGORITHM = "gateway.rateLimit.algorithm";
    public static final String RATE_LIMIT_KEY = "gateway.rateLimit.key";
    public static final String RATE_LIMIT_PERMITS_PER_SECOND = "gateway.rateLimit.permitsPerSecond";
    public static final String RATE_LIMIT_BURST = "gateway.rateLimit.burst";
    public static final String RATE_LIMIT_LIMIT = "gateway.rateLimit.limit";
    public static final String RATE_LIMIT_WINDOW_SECONDS = "gateway.rateLimit.windowSeconds";
    public static final String TRACE_ENABLED = "gateway.trace.enabled";
    public static final String TRACE_SLOW_THRESHOLD_MILLIS = "gateway.trace.slowThresholdMillis";
    public static final String TRACE_SAMPLE_RATE = "gateway.trace.sampleRate";

    public static final Set<String> BOOLEAN_KEYS =
            Set.of(FILTER_ENABLED, RATE_LIMIT_ENABLED, METRICS_ENABLED, TRACE_ENABLED);
}
