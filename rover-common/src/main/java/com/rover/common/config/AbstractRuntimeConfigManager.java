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
    /** 当前版本尚未识别的 overlay 项，等可选插件注册后再接管。 */
    private final Map<String, String> deferredOverlayValues = new ConcurrentHashMap<>();
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

    /** 注册一个可热更新、非敏感的内置配置项（自由输入）。 */
    protected final void addConfig(String key, String value, String defaultValue, String description) {
        addConfig(key, value, defaultValue, description, List.of());
    }

    /** 注册一个可热更新、非敏感的内置配置项，并附带预设可选值（管理端渲染为可选择项，仍允许自定义输入）。 */
    protected final void addConfig(
            String key, String value, String defaultValue, String description, List<String> options) {
        addConfig(key, value, defaultValue, description, options, false);
    }

    /** 注册内置配置项，可显式标记敏感值；对外列表会脱敏，overlay 仍保存真实值。 */
    protected final void addConfig(
            String key,
            String value,
            String defaultValue,
            String description,
            List<String> options,
            boolean sensitive) {
        configs.put(
                key,
                new ConfigItem(
                        key, value, defaultValue, description, ConfigApplyMode.HOT_RELOAD, sensitive,
                        options == null ? List.of() : List.copyOf(options)));
    }

    /**
     * 注册可选扩展的配置项；已有值不被默认值覆盖。
     * 插件在启动配置读取之后才加载时，会在这里接管之前暂存的 overlay 值。
     */
    protected final void addConfigIfAbsent(
            String key,
            String defaultValue,
            String description,
            List<String> options,
            boolean sensitive) {
        configs.putIfAbsent(
                key,
                new ConfigItem(
                        key,
                        defaultValue,
                        defaultValue,
                        description,
                        ConfigApplyMode.HOT_RELOAD,
                        sensitive,
                        options == null ? List.of() : List.copyOf(options)));
        String deferredValue = deferredOverlayValues.remove(key);
        if (deferredValue != null) {
            seed(key, deferredValue);
        }
    }

    /** 读取已注册配置的当前值，供可选扩展在装配时应用。 */
    protected final String currentValue(String key) {
        ConfigItem item = configs.get(key);
        return item == null ? null : item.getValue();
    }

    /**
     * 刷新已注册配置的候选值。候选值只影响管理端展示，不限制自由输入的配置项。
     */
    protected final void replaceConfigOptions(String key, List<String> options) {
        ConfigItem item = configs.get(key);
        if (item != null) {
            item.setOptions(options == null ? List.of() : List.copyOf(options));
        }
    }

    /** 把一条配置变更事件应用到具体运行时，由子类桥接到自己的 Applier。 */
    protected abstract void applyChange(ConfigChangeEvent event);

    /** 更新前的值校验，非法时抛 {@link IllegalArgumentException}。 */
    protected abstract void validate(String key, String value);

    /** 值归一化钩子：默认原样返回，子类可覆盖（如布尔统一为 true/false）。 */
    protected String normalize(String key, String value) {
        return value;
    }

    /** 是否允许该 key 从运行时 overlay 读取并落盘；启动级配置可由子类排除。 */
    protected boolean runtimeOverlayKey(String key) {
        return true;
    }

    /** 启动时灌入初始值，仅覆盖已注册且值非 null 的配置项，不触发 apply。 */
    public void seed(String key, String value) {
        ConfigItem item = configs.get(key);
        if (item != null && value != null) {
            String normalized = value.trim();
            validate(key, normalized);
            item.setValue(normalize(key, normalized));
        }
    }

    /** 若 overlay 文件存在则加载覆盖，优先级高于 seed 进来的 YAML 值。 */
    public void loadOverlayIfPresent() {
        if (!overlayStore.exists()) {
            return;
        }
        Map<String, String> overlay = overlayStore.load();
        for (Map.Entry<String, String> entry : overlay.entrySet()) {
            if (!runtimeOverlayKey(entry.getKey())) {
                continue;
            }
            if (supports(entry.getKey())) {
                try {
                    seed(entry.getKey(), entry.getValue());
                } catch (IllegalArgumentException ex) {
                    throw new IllegalStateException(
                        componentName + " 配置覆盖非法: key=" + entry.getKey(), ex);
                }
            } else {
                // 插件可能在启动配置读取之后才被加载，先保留其值，避免下次保存时丢失。
                deferredOverlayValues.put(entry.getKey(), entry.getValue());
            }
        }
        log.info("已加载 {} 配置覆盖: path={}, size={}",
                componentName, overlayStore.getPath().toAbsolutePath(), overlay.size());
    }

    /** 把当前全量配置以「空旧值 + 当前值」事件重放给运行时，用于 bind 后落地。 */
    public void reapplyAll() {
        for (ConfigItem item : rawConfigCopies()) {
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
            ConfigItem copy = copyOf(item);
            if (copy.isSensitive()) {
                copy.setValue(ConfigValues.MASKED);
                copy.setDefaultValue(ConfigValues.MASKED);
            }
            items.add(copy);
        }
        items.sort(Comparator.comparing(ConfigItem::getKey));
        return items;
    }

    @Override
    public boolean supports(String key) {
        return configs.containsKey(key);
    }

    @Override
    public synchronized ConfigChangeEvent updateConfig(String key, String value) {
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
        ConfigChangeEvent event = new ConfigChangeEvent(
                item.getKey(),
                oldValue,
                normalized,
                item.getApplyMode(),
                System.currentTimeMillis());
        applyChange(event);
        item.setValue(normalized);
        try {
            persistOverlay();
            return event;
        } catch (RuntimeException persistError) {
            item.setValue(oldValue);
            try {
                applyChange(new ConfigChangeEvent(
                        item.getKey(),
                        normalized,
                        oldValue,
                        item.getApplyMode(),
                        System.currentTimeMillis()));
            } catch (RuntimeException rollbackError) {
                persistError.addSuppressed(rollbackError);
            }
            throw persistError;
        }
    }

    /** 把当前全量配置按键序写入 overlay 文件。 */
    private void persistOverlay() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : deferredOverlayValues.entrySet()) {
            if (runtimeOverlayKey(entry.getKey())) {
                snapshot.put(entry.getKey(), entry.getValue());
            }
        }
        for (ConfigItem item : rawConfigCopies()) {
            if (runtimeOverlayKey(item.getKey())) {
                snapshot.put(item.getKey(), item.getValue());
            }
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
                item.isSensitive(),
                item.getOptions() == null ? List.of() : List.copyOf(item.getOptions()));
    }

    private List<ConfigItem> rawConfigCopies() {
        List<ConfigItem> items = new ArrayList<>(configs.size());
        for (ConfigItem item : configs.values()) {
            items.add(copyOf(item));
        }
        items.sort(Comparator.comparing(ConfigItem::getKey));
        return items;
    }
}
