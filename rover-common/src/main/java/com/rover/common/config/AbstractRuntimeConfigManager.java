package com.rover.common.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-04 10:20:00
 * Description: {@link RuntimeConfigManager} 通用实现骨架：子类注册配置项并实现 validate/applyChange 即接入热更新
 */
@Slf4j
public abstract class AbstractRuntimeConfigManager implements RuntimeConfigManager {

    /** 配置项注册表：key -> ConfigItem */
    private final Map<String, ConfigItem> configs = new ConcurrentHashMap<>();
    /** overlay 持久化存储，供 status 接口展示路径 */
    @Getter
    private final RuntimeConfigOverlayStore overlayStore;
    /** 组件名，用于日志与异常文案 */
    private final String componentName;

    protected AbstractRuntimeConfigManager(Path overlayPath, String componentName) {
        this(new RuntimeConfigOverlayStore(overlayPath), componentName);
    }

    protected AbstractRuntimeConfigManager(RuntimeConfigOverlayStore overlayStore, String componentName) {
        this.overlayStore = overlayStore;
        this.componentName = componentName;
    }

    /** 注册一个可热更新、非敏感的内置配置项。 */
    protected final void addConfig(String key, String value, String defaultValue, String description) {
        configs.put(
                key,
                new ConfigItem(key, value, defaultValue, description, ConfigApplyMode.HOT_RELOAD, false));
    }

    /** 把一条配置变更事件应用到具体运行时，由子类桥接到自己的 Applier。 */
    protected abstract void applyChange(ConfigChangeEvent event);

    /** 更新前的值校验，非法时抛 {@link IllegalArgumentException}。 */
    protected abstract void validate(String key, String value);

    /** 值归一化钩子：默认原样返回，子类可覆盖（如布尔统一为 true/false）。 */
    protected String normalize(String key, String value) {
        return value;
    }

    /** 启动时灌入初始值，仅覆盖已注册且值非 null 的配置项，不触发 apply。 */
    public void seed(String key, String value) {
        ConfigItem item = configs.get(key);
        if (item != null && value != null) {
            item.setValue(value);
        }
    }

    /** 若 overlay 文件存在则加载覆盖，优先级高于 seed 进来的 YAML 值。 */
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
        log.info("已加载 {} 配置覆盖: path={}, size={}",
                componentName, overlayStore.getPath().toAbsolutePath(), overlay.size());
    }

    /** 把当前全量配置以「空旧值 + 当前值」事件重放给运行时，用于 bind 后落地。 */
    public void reapplyAll() {
        for (ConfigItem item : listConfigs()) {
            ConfigChangeEvent event = new ConfigChangeEvent(
                    item.getKey(),
                    null,
                    item.getValue(),
                    item.getApplyMode(),
                    System.currentTimeMillis());
            applyChange(event);
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
            throw new IllegalArgumentException("未知 " + componentName + " 配置项：" + key);
        }
        if (!item.isHotReloadable()) {
            throw new UnsupportedOperationException("配置项需重启生效：" + key);
        }

        String oldValue = item.getValue();
        String normalized = value == null ? "" : value.trim();
        validate(key, normalized);
        normalized = normalize(key, normalized);
        item.setValue(normalized);

        ConfigChangeEvent event = new ConfigChangeEvent(
                item.getKey(),
                oldValue,
                normalized,
                item.getApplyMode(),
                System.currentTimeMillis());
        applyChange(event);
        persistOverlay();
        return event;
    }

    /** 把当前全量配置按键序写入 overlay 文件。 */
    private void persistOverlay() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (ConfigItem item : listConfigs()) {
            snapshot.put(item.getKey(), item.getValue());
        }
        overlayStore.save(snapshot);
    }

    /** 构造 ConfigItem 防御性副本。 */
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
