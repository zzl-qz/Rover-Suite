package com.rover.gateway.core.config;

import com.rover.common.config.ConfigChangeEvent;
import com.rover.gateway.core.runtime.GatewayRuntime;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 14:59:00
 * Description: 把配置变更应用到 Gateway 运行时
 */
@Slf4j
public class GatewayRuntimeConfigApplier {

    private volatile GatewayRuntime runtime;

    public void bind(GatewayRuntime runtime) {
        this.runtime = runtime;
    }

    public void apply(ConfigChangeEvent event) {
        GatewayRuntime current = runtime;
        if (current == null || event == null) {
            return;
        }
        String key = event.getKey();
        String value = event.getNewValue();
        switch (key) {
            case "gateway.filter.enabled" -> current.applyFilterEnabled(Boolean.parseBoolean(value));
            case "gateway.request.timeoutMillis" -> current.applyRequestTimeoutMillis(parsePositiveLong(value, key));
            case "gateway.loadbalance.strategy" -> current.applyLoadBalanceStrategy(value);
            default -> log.warn("忽略未支持热更新的 Gateway 配置: {}", key);
        }
        log.info("Gateway 配置已热更新: {}={} (old={})", key, value, event.getOldValue());
    }

    private static long parsePositiveLong(String value, String key) {
        long parsed = Long.parseLong(value.trim());
        if (parsed <= 0) {
            throw new IllegalArgumentException(key + " 必须大于 0");
        }
        return parsed;
    }
}
