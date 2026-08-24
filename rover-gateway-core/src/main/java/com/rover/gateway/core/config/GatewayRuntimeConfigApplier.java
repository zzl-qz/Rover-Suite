package com.rover.gateway.core.config;

import com.rover.common.config.ConfigApplier;
import com.rover.common.config.ConfigChangeEvent;
import com.rover.gateway.core.runtime.GatewayRuntime;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-09 13:40:00
 * Description: 把配置变更事件应用到 Gateway 运行时
 */
@Slf4j
public class GatewayRuntimeConfigApplier implements ConfigApplier {

    /** 绑定的网关运行时，volatile 保证 bind 后可见。 */
    private volatile GatewayRuntime runtime;

    /** 绑定 GatewayRuntime，之后 apply 才会生效。 */
    public void bind(GatewayRuntime runtime) {
        this.runtime = runtime;
    }

    /** 把配置变更事件应用到 GatewayRuntime。 */
    public void apply(ConfigChangeEvent event) {
        GatewayRuntime current = runtime;
        if (current == null || event == null) {
            return;
        }
        String key = event.getKey();
        String value = event.getNewValue();
        switch (key) {
            case GatewayRuntimeConfigKeys.FILTER_ENABLED -> current.applyFilterEnabled(Boolean.parseBoolean(value));
            case GatewayRuntimeConfigKeys.RATE_LIMIT_ENABLED ->
                    current.applyRateLimitEnabled(Boolean.parseBoolean(value));
            case GatewayRuntimeConfigKeys.RATE_LIMIT_ALGORITHM -> current.applyRateLimitAlgorithm(value);
            case GatewayRuntimeConfigKeys.RATE_LIMIT_KEY -> current.applyRateLimitKey(value);
            case GatewayRuntimeConfigKeys.RATE_LIMIT_PERMITS_PER_SECOND ->
                    current.applyRateLimitPermitsPerSecond(parsePositiveLong(value, key));
            case GatewayRuntimeConfigKeys.RATE_LIMIT_BURST ->
                    current.applyRateLimitBurst(parsePositiveLong(value, key));
            case GatewayRuntimeConfigKeys.RATE_LIMIT_LIMIT ->
                    current.applyRateLimitLimit(parsePositiveLong(value, key));
            case GatewayRuntimeConfigKeys.RATE_LIMIT_WINDOW_SECONDS ->
                    current.applyRateLimitWindowSeconds(parsePositiveInt(value, key));
            case GatewayRuntimeConfigKeys.REQUEST_TIMEOUT_MILLIS ->
                    current.applyRequestTimeoutMillis(parsePositiveLong(value, key));
            case GatewayRuntimeConfigKeys.LOAD_BALANCE_STRATEGY -> current.applyLoadBalanceStrategy(value);
            case GatewayRuntimeConfigKeys.METRICS_ENABLED -> current.applyMetricsEnabled(Boolean.parseBoolean(value));
            case GatewayRuntimeConfigKeys.METRICS_WINDOW_SECONDS ->
                    current.applyMetricsWindowSeconds(parsePositiveInt(value, key));
            case GatewayRuntimeConfigKeys.TRACE_ENABLED -> current.applyTraceEnabled(Boolean.parseBoolean(value));
            case GatewayRuntimeConfigKeys.TRACE_SLOW_THRESHOLD_MILLIS ->
                    current.applyTraceSlowThresholdMillis(parsePositiveLong(value, key));
            case GatewayRuntimeConfigKeys.TRACE_SAMPLE_RATE ->
                    current.applyTraceSampleRate(Double.parseDouble(value.trim()));
            default -> log.warn("忽略未支持热更新的 Gateway 配置: {}", key);
        }
        log.info("Gateway 配置已热更新: {}={} (old={})", key, value, event.getOldValue());
    }

    /** 解析正整数配置值。 */
    private static long parsePositiveLong(String value, String key) {
        long parsed = Long.parseLong(value.trim());
        if (parsed <= 0) {
            throw new IllegalArgumentException(key + " 必须大于 0");
        }
        return parsed;
    }

    /** 解析正 int 配置值。 */
    private static int parsePositiveInt(String value, String key) {
        int parsed = Integer.parseInt(value.trim());
        if (parsed <= 0) {
            throw new IllegalArgumentException(key + " 必须大于 0");
        }
        return parsed;
    }
}
