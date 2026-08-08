package com.rover.gateway.core.config;

import com.rover.common.config.ConfigApplyMode;
import com.rover.common.config.ConfigChangeEvent;
import com.rover.common.config.ConfigItem;
import com.rover.common.config.RuntimeConfigManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Author: Daylight
 * Created: 2026-08-08 14:59:00
 * Description: 维护 Gateway 可热更新运行时配置
 */
public class GatewayRuntimeConfigManager implements RuntimeConfigManager {

    private final Map<String, ConfigItem> configs = new ConcurrentHashMap<>();
    private final GatewayRuntimeConfigApplier applier;

    public GatewayRuntimeConfigManager() {
        this(new GatewayRuntimeConfigApplier());
    }

    public GatewayRuntimeConfigManager(GatewayRuntimeConfigApplier applier) {
        this.applier = applier;
        addConfig("gateway.route.rules", "[]", "[]", "网关路由规则");
        addConfig("gateway.filter.enabled", "true", "true", "网关过滤器总开关");
        addConfig("gateway.loadbalance.strategy", "round_robin", "round_robin", "网关负载均衡策略");
        addConfig("gateway.request.timeoutMillis", "3000", "3000", "网关请求超时时间");
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
            throw new IllegalArgumentException("未知 Gateway 配置项：" + key);
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
