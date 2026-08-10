package com.rover.gateway.core.config;

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
 * Description: 维护 Gateway 可热更新运行时配置，并落盘 overlay
 */
@Slf4j
public class GatewayRuntimeConfigManager implements RuntimeConfigManager {

    public static final Path DEFAULT_OVERLAY = Path.of("config", "gateway-runtime.overlay.json");

    private final Map<String, ConfigItem> configs = new ConcurrentHashMap<>();
    private final GatewayRuntimeConfigApplier applier;
    private final RuntimeConfigOverlayStore overlayStore;

    public GatewayRuntimeConfigManager() {
        this(new GatewayRuntimeConfigApplier(), new RuntimeConfigOverlayStore(DEFAULT_OVERLAY));
    }

    public GatewayRuntimeConfigManager(GatewayRuntimeConfigApplier applier) {
        this(applier, new RuntimeConfigOverlayStore(DEFAULT_OVERLAY));
    }

    public GatewayRuntimeConfigManager(
            GatewayRuntimeConfigApplier applier, RuntimeConfigOverlayStore overlayStore) {
        this.applier = applier;
        this.overlayStore = overlayStore;
        addConfig("gateway.filter.enabled", "true", "true", "网关过滤器总开关");
        addConfig("gateway.loadbalance.strategy", "round_robin", "round_robin", "网关负载均衡策略");
        addConfig("gateway.request.timeoutMillis", "30000", "30000", "网关请求超时时间");
    }

    public GatewayRuntimeConfigApplier getApplier() {
        return applier;
    }

    public RuntimeConfigOverlayStore getOverlayStore() {
        return overlayStore;
    }

    /** 启动时用真实 YAML 灌初值，不触发 apply */
    public void seed(String key, String value) {
        ConfigItem item = configs.get(key);
        if (item != null && value != null) {
            item.setValue(value);
        }
    }

    /** YAML 之后叠 overlay，仍不 apply */
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
        log.info("已加载 Gateway 配置覆盖: path={}, size={}",
                overlayStore.getPath().toAbsolutePath(), overlay.size());
    }

    /** applier bind 之后，把当前值真正打进运行时 */
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
            throw new IllegalArgumentException("未知 Gateway 配置项：" + key);
        }
        if (!item.isHotReloadable()) {
            throw new UnsupportedOperationException("配置项需重启生效：" + key);
        }

        String oldValue = item.getValue();
        String normalized = value == null ? "" : value.trim();
        validate(key, normalized);
        if ("gateway.loadbalance.strategy".equals(key)) {
            normalized = normalized.toLowerCase();
        }
        if ("gateway.filter.enabled".equals(key)) {
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
        if ("gateway.loadbalance.strategy".equals(key)) {
            String strategy = value.toLowerCase();
            if (!"round_robin".equals(strategy) && !"random".equals(strategy)) {
                throw new IllegalArgumentException("loadbalance.strategy 仅支持 round_robin / random");
            }
        }
        if ("gateway.request.timeoutMillis".equals(key)) {
            long timeout = Long.parseLong(value);
            if (timeout <= 0) {
                throw new IllegalArgumentException("timeoutMillis 必须大于 0");
            }
        }
        if ("gateway.filter.enabled".equals(key)
                && !"true".equalsIgnoreCase(value)
                && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("filter.enabled 仅支持 true/false");
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
