package com.rover.nameserver.core.config;

import com.rover.common.config.ConfigApplyMode;
import com.rover.common.config.ConfigChangeEvent;
import com.rover.common.config.ConfigItem;
import com.rover.common.config.RuntimeConfigManager;
import com.rover.common.config.RuntimeConfigOverlayStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 14:59:00
 * Description: 维护 Nameserver 可热更新运行时配置，并落盘 overlay
 */
@Slf4j
public class NameserverRuntimeConfigManager implements RuntimeConfigManager {

    public static final Path DEFAULT_OVERLAY = Path.of("config", "nameserver-runtime.overlay.json");

    private final Map<String, ConfigItem> configs = new ConcurrentHashMap<>();
    private final NameserverRuntimeConfigApplier applier;
    private final RuntimeConfigOverlayStore overlayStore;

    public NameserverRuntimeConfigManager() {
        this(new NameserverRuntimeConfigApplier(), new RuntimeConfigOverlayStore(DEFAULT_OVERLAY));
    }

    public NameserverRuntimeConfigManager(NameserverRuntimeConfigApplier applier) {
        this(applier, new RuntimeConfigOverlayStore(DEFAULT_OVERLAY));
    }

    public NameserverRuntimeConfigManager(
            NameserverRuntimeConfigApplier applier, RuntimeConfigOverlayStore overlayStore) {
        this.applier = applier;
        this.overlayStore = overlayStore;
        addConfig("nameserver.health.checkIntervalMillis", "5000", "5000", "注册中心健康检查间隔");
        addConfig("nameserver.heartbeat.timeoutMillis", "15000", "15000", "注册中心心跳超时时间");
        addConfig("nameserver.instance.expireMillis", "30000", "30000", "注册中心实例过期时间");
        addConfig("nameserver.push.enabled", "true", "true", "注册中心服务变更推送开关");
    }

    public NameserverRuntimeConfigApplier getApplier() {
        return applier;
    }

    public RuntimeConfigOverlayStore getOverlayStore() {
        return overlayStore;
    }

    public void seed(String key, String value) {
        ConfigItem item = configs.get(key);
        if (item != null && value != null) {
            item.setValue(value);
        }
    }

    public void loadOverlayIfPresent() {
        if (!overlayStore.exists()) {
            return;
        }
        Map<String, String> overlay = overlayStore.load();
        for (Map.Entry<String, String> entry : overlay.entrySet()) {
            if (supports(entry.getKey())) {
                seed(entry.getKey(), entry.getValue());
            }
        }
        log.info("已加载 Nameserver 配置覆盖: path={}, size={}",
                overlayStore.getPath().toAbsolutePath(), overlay.size());
    }

    public void reapplyAll() {
        for (ConfigItem item : listConfigs()) {
            ConfigChangeEvent event = new ConfigChangeEvent(
                    item.getKey(),
                    null,
                    item.getValue(),
                    item.getApplyMode(),
                    System.currentTimeMillis());
            applier.apply(event);
        }
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
        if (!item.isHotReloadable()) {
            throw new UnsupportedOperationException("配置项需重启生效：" + key);
        }

        String oldValue = item.getValue();
        String normalized = value == null ? "" : value.trim();
        validate(key, normalized);
        if ("nameserver.push.enabled".equals(key)) {
            normalized = Boolean.parseBoolean(normalized) ? "true" : "false";
        }
        item.setValue(normalized);

        ConfigChangeEvent event = new ConfigChangeEvent(
                item.getKey(),
                oldValue,
                normalized,
                item.getApplyMode(),
                System.currentTimeMillis());
        applier.apply(event);
        persistOverlay();
        return event;
    }

    private void persistOverlay() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (ConfigItem item : listConfigs()) {
            snapshot.put(item.getKey(), item.getValue());
        }
        overlayStore.save(snapshot);
    }

    private void validate(String key, String value) {
        if (key.endsWith("Millis")) {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException(key + " 必须大于 0");
            }
        }
        if ("nameserver.push.enabled".equals(key)
                && !"true".equalsIgnoreCase(value)
                && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("push.enabled 仅支持 true/false");
        }
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
