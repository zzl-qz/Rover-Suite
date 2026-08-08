package com.rover.nameserver.core.config;

import com.rover.common.config.ConfigApplyMode;
import com.rover.common.config.ConfigChangeEvent;
import com.rover.common.config.ConfigItem;
import com.rover.common.config.RuntimeConfigManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * Author: Daylight
 * Created: 2026-08-08 14:59:00
 * Description: 维护 Nameserver 可热更新运行时配置
 */
public class NameserverRuntimeConfigManager implements RuntimeConfigManager {

    private final Map<String, ConfigItem> configs = new ConcurrentHashMap<>();
    private final NameserverRuntimeConfigApplier applier;

    public NameserverRuntimeConfigManager() {
        this(new NameserverRuntimeConfigApplier());
    }

    public NameserverRuntimeConfigManager(NameserverRuntimeConfigApplier applier) {
        this.applier = applier;
        addConfig("nameserver.health.checkIntervalMillis", "5000", "5000", "注册中心健康检查间隔");
        addConfig("nameserver.heartbeat.timeoutMillis", "15000", "15000", "注册中心心跳超时时间");
        addConfig("nameserver.instance.expireMillis", "30000", "30000", "注册中心实例过期时间");
        addConfig("nameserver.push.enabled", "true", "true", "注册中心服务变更推送开关");
    }

    @Override
    public List<ConfigItem> listConfigs() {
        List<ConfigItem> items = new ArrayList<>(configs.size());
        for (ConfigItem item : configs.values()) {
            items.add(copyOf(item));
        }
        items.sort(Comparator.comparing(ConfigItem::getKey));
        return items;
    }

    @Override
    public boolean supports(String key) {
        return configs.containsKey(key);
    }

    @Override
    public ConfigChangeEvent updateConfig(String key, String value) {
        ConfigItem item = configs.get(key);
        if (item == null) {
            throw new IllegalArgumentException("未知 Nameserver 配置项：" + key);
        }

        String oldValue = item.getValue();
        item.setValue(value);

        ConfigChangeEvent event = new ConfigChangeEvent(
                item.getKey(),
                oldValue,
                value,
                item.getApplyMode(),
                System.currentTimeMillis());
        applier.apply(event);
        return event;
    }

    private void addConfig(String key, String value, String defaultValue, String description) {
        configs.put(
                key,
                new ConfigItem(
                        key,
                        value,
                        defaultValue,
                        description,
                        ConfigApplyMode.HOT_RELOAD,
                        false));
    }

    private static ConfigItem copyOf(ConfigItem item) {
        return new ConfigItem(
                item.getKey(),
                item.getValue(),
                item.getDefaultValue(),
                item.getDescription(),
                item.getApplyMode(),
                item.isSensitive());
    }
}
