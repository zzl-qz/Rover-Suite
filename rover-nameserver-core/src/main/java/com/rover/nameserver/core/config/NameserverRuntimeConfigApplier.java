package com.rover.nameserver.core.config;

import com.rover.common.config.ConfigApplier;
import com.rover.common.config.ConfigChangeEvent;
import com.rover.nameserver.core.runtime.NameserverRuntime;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 14:59:00
 * Description: 把配置变更应用到 Nameserver 运行时
 *
 * 这个类是什么：配置热更新的落点执行器。
 * 核心职责：把 ConfigChangeEvent 翻译成对 HealthChecker、PushService 等组件的 setter 调用；
 * runtime 未绑定或 event 为 null 时直接跳过。
 * 被谁用：NameserverRuntimeConfigManager 在 updateConfig / reapplyAll 时调用；
 * NameserverTcpServer 启动时 bind(runtime)。
 */
@Slf4j
public class NameserverRuntimeConfigApplier implements ConfigApplier {

    /** 当前绑定的 Nameserver 运行时，volatile 保证多线程可见性（bind 发生在启动阶段） */
    private volatile NameserverRuntime runtime;

    /**
     * 绑定运行时引用。通常在 NameserverTcpServer 启动装配时调用一次；
     * 绑定前调用 {@link #apply} 会因 runtime 为 null 而直接跳过。
     *
     * @param runtime 运行时顶层对象，含 HealthChecker / PushService 等可热更新组件
     */
    public void bind(NameserverRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * 应用一条配置变更事件：按配置项 key 分发到对应组件的 setter。
     *
     * @param event 配置变更事件，含 key / 新旧值；null 或 runtime 未绑定时直接忽略
     */
    public void apply(ConfigChangeEvent event) {
        NameserverRuntime current = runtime;
        if (current == null || event == null) {
            return;
        }
        String key = event.getKey();
        String value = event.getNewValue();
        // 按 key 分发：时间类配置落到 HealthChecker，开关类配置落到 PushService；
        // 未知 key 只告警不报错，避免单条配置问题拖垮整体热更新
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

    /**
     * 把字符串安全解析为正数。
     *
     * @param value 待解析的字符串数值
     * @param key   配置项 key，用于异常提示
     * @return 大于 0 的 long
     * @throws IllegalArgumentException 解析失败或数值 <= 0 时抛出
     */
    private static long parsePositiveLong(String value, String key) {
        long parsed = Long.parseLong(value.trim());
        if (parsed <= 0) {
            throw new IllegalArgumentException(key + " 必须大于 0");
        }
        return parsed;
    }
}