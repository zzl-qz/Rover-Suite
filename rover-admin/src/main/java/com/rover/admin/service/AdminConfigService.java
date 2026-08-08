package com.rover.admin.service;

import com.rover.common.config.ConfigApplyMode;
import com.rover.common.config.ConfigChangeEvent;
import com.rover.common.config.ConfigItem;
import com.rover.common.config.RuntimeConfigManager;
import com.rover.gateway.core.config.GatewayRuntimeConfigManager;
import com.rover.nameserver.core.config.NameserverRuntimeConfigManager;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Author: Daylight
 * Created: 2026-08-08 11:37:00
 * Description: 聚合核心组件暴露给管理端的配置项
 */
@Service
public class AdminConfigService {

    private final List<RuntimeConfigManager> configManagers = List.of(
            new GatewayRuntimeConfigManager(),
            new NameserverRuntimeConfigManager());

    public List<ConfigItem> listConfigs() {
        List<ConfigItem> items = new ArrayList<>();
        for (RuntimeConfigManager manager : configManagers) {
            items.addAll(manager.listConfigs());
        }
        return items;
    }

    public ConfigUpdateResult updateConfig(String key, String value) {
        for (RuntimeConfigManager manager : configManagers) {
            if (!manager.supports(key)) {
                continue;
            }

            ConfigChangeEvent event = manager.updateConfig(key, value);
            String message = ConfigApplyMode.HOT_RELOAD.equals(event.getApplyMode())
                    ? "已热更新"
                    : "已保存，重启后生效";
            ConfigItem item = findConfigItem(manager, key);
            return new ConfigUpdateResult(item, event, message);
        }

        throw new IllegalArgumentException("未知配置项：" + key);
    }

    private static ConfigItem findConfigItem(RuntimeConfigManager manager, String key) {
        for (ConfigItem item : manager.listConfigs()) {
            if (key.equals(item.getKey())) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知配置项：" + key);
    }
}
