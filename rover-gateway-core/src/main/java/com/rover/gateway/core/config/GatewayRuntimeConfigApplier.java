package com.rover.gateway.core.config;

import com.rover.common.config.ConfigApplier;
import com.rover.common.config.ConfigChangeEvent;
import com.rover.gateway.core.runtime.GatewayRuntime;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 14:59:00
 * Description: 把配置变更应用到 Gateway 运行时
 *
 * 这个类是什么：ConfigChangeEvent 到 GatewayRuntime 方法的桥接器。
 * 核心职责：bind 运行时引用；按 key 分发到 applyFilterEnabled、
 * applyRequestTimeoutMillis、applyLoadBalanceStrategy。
 * 被谁用：GatewayRuntimeConfigManager 在 updateConfig 和 reapplyAll 时调用。
 */
@Slf4j
public class GatewayRuntimeConfigApplier implements ConfigApplier {

    /** 绑定的网关运行时，volatile 保证 bind 后可见。 */
    private volatile GatewayRuntime runtime;

    /**
     * 绑定 GatewayRuntime，之后 apply 才会生效。
     *
     * @param runtime 网关运行时
     */
    public void bind(GatewayRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * 把配置变更事件应用到 GatewayRuntime。
     *
     * @param event 配置变更事件，含 key 和新值
     */
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

    /**
     * 解析正整数配置值。
     *
     * @param value 字符串值
     * @param key   配置 key，用于异常信息
     * @return 解析后的 long
     * @throws IllegalArgumentException 非正数
     */
    private static long parsePositiveLong(String value, String key) {
        long parsed = Long.parseLong(value.trim());
        if (parsed <= 0) {
            throw new IllegalArgumentException(key + " 必须大于 0");
        }
        return parsed;
    }
}
