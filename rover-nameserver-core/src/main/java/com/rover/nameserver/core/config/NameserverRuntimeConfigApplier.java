package com.rover.nameserver.core.config;

import com.rover.common.config.ConfigChangeEvent;
import com.rover.nameserver.core.runtime.NameserverRuntime;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 14:59:00
 * Description: 把配置变更应用到 Nameserver 运行时
 */
@Slf4j
public class NameserverRuntimeConfigApplier {

    private volatile NameserverRuntime runtime;

    public void bind(NameserverRuntime runtime) {
        this.runtime = runtime;
    }

    public void apply(ConfigChangeEvent event) {
        NameserverRuntime current = runtime;
        if (current == null || event == null) {
            return;
        }
        String key = event.getKey();
        String value = event.getNewValue();
        switch (key) {
            case "nameserver.health.checkIntervalMillis" ->
                    current.getHealthChecker().setCheckIntervalMillis(parsePositiveLong(value, key));
            case "nameserver.heartbeat.timeoutMillis" ->
                    current.getHealthChecker().setHeartbeatTimeoutMillis(parsePositiveLong(value, key));
            case "nameserver.instance.expireMillis" ->
                    current.getHealthChecker().setInstanceExpireMillis(parsePositiveLong(value, key));
            case "nameserver.push.enabled" ->
                    current.getPushService().setPushEnabled(Boolean.parseBoolean(value));
            default -> log.warn("忽略未支持热更新的 Nameserver 配置: {}", key);
        }
        log.info("Nameserver 配置已热更新: {}={} (old={})", key, value, event.getOldValue());
    }

    private static long parsePositiveLong(String value, String key) {
        long parsed = Long.parseLong(value.trim());
        if (parsed <= 0) {
            throw new IllegalArgumentException(key + " 必须大于 0");
        }
        return parsed;
    }
}
