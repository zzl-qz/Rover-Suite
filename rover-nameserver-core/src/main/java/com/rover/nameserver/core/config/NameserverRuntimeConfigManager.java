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
 *
 * 这个类是什么：Nameserver 侧 RuntimeConfigManager 的实现。
 * 核心职责：①注册并维护可热更新配置项（健康检查间隔、心跳超时、过期时间、推送开关）；
 * ②每次更新经 NameserverRuntimeConfigApplier 实时应用到运行时组件；
 * ③通过 RuntimeConfigOverlayStore 持久化 overlay，重启后 loadOverlayIfPresent 恢复。
 * 被谁用：NameserverTcpServer 装配；HTTP Admin 经其 updateConfig 热更新。
 */
@Slf4j
public class NameserverRuntimeConfigManager implements RuntimeConfigManager {

    /** overlay 落盘路径：工作目录下 config/nameserver-runtime.overlay.json */
    public static final Path DEFAULT_OVERLAY = Path.of("config", "nameserver-runtime.overlay.json");

    /** 配置项注册表：key -> ConfigItem，ConcurrentHashMap 保证并发读写安全 */
    private final Map<String, ConfigItem> configs = new ConcurrentHashMap<>();
    /** 变更应用执行器，每次更新/重放都经它落到运行时组件 */
    private final NameserverRuntimeConfigApplier applier;
    /** overlay 持久化存储 */
    private final RuntimeConfigOverlayStore overlayStore;

    /** 使用默认 Applier 与默认 overlay 路径构造，并注册全部内置配置项 */
    public NameserverRuntimeConfigManager() {
        this(new NameserverRuntimeConfigApplier(), new RuntimeConfigOverlayStore(DEFAULT_OVERLAY));
    }

    /** 使用指定 Applier（便于测试注入）与默认 overlay 路径构造 */
    public NameserverRuntimeConfigManager(NameserverRuntimeConfigApplier applier) {
        this(applier, new RuntimeConfigOverlayStore(DEFAULT_OVERLAY));
    }

    /**
     * 完整构造：注入 Applier 与 overlay 存储，注册内置配置项。
     * 内置项均为 HOT_RELOAD 模式且非敏感，默认值与初值相同。
     *
     * @param applier      变更应用执行器
     * @param overlayStore overlay 持久化存储
     */
    public NameserverRuntimeConfigManager(
            NameserverRuntimeConfigApplier applier, RuntimeConfigOverlayStore overlayStore) {
        this.applier = applier;
        this.overlayStore = overlayStore;
        addConfig("nameserver.health.checkIntervalMillis", "5000", "5000", "注册中心健康检查间隔");
        addConfig("nameserver.heartbeat.timeoutMillis", "15000", "15000", "注册中心心跳超时时间");
        addConfig("nameserver.instance.expireMillis", "30000", "30000", "注册中心实例过期时间");
        addConfig("nameserver.push.enabled", "true", "true", "注册中心服务变更推送开关");
    }

    /** 返回变更应用执行器，供装配方 bind 运行时 */
    public NameserverRuntimeConfigApplier getApplier() {
        return applier;
    }

    /** 返回 overlay 持久化存储 */
    public RuntimeConfigOverlayStore getOverlayStore() {
        return overlayStore;
    }

    /**
     * 灌注初始值：仅当配置项已注册且传入值非 null 时覆盖其当前值。
     * 用于启动阶段把 YAML 中的值灌入，再叠加 overlay 中的持久化值。
     *
     * @param key   配置项 key
     * @param value 初始值；null 或未注册的 key 直接忽略
     */
    public void seed(String key, String value) {
        ConfigItem item = configs.get(key);
        if (item != null && value != null) {
            item.setValue(value);
        }
    }

    /**
     * 若 overlay 文件存在则加载并覆盖到已注册配置项上，
     * 使上次运行期间持久化的配置在重启后仍生效（优先级：overlay 高于 YAML）。
     */
    public void loadOverlayIfPresent() {
        if (!overlayStore.exists()) {
            return;
        }
        Map<String, String> overlay = overlayStore.load();
        for (Map.Entry<String, String> entry : overlay.entrySet()) {
            // 只接受本管理器已知的配置项，陌生 key 静默跳过
            if (supports(entry.getKey())) {
                seed(entry.getKey(), entry.getValue());
            }
        }
        log.info("已加载 Nameserver 配置覆盖: path={}, size={}",
                overlayStore.getPath().toAbsolutePath(), overlay.size());
    }

    /**
     * 把当前所有配置项以「空旧值 + 当前值」事件的形式重放给 Applier。
     * 用于运行时装配完成（runtime 绑定）后，把注册表里的值一次性落到运行时组件。
     */
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

    /**
     * 返回全部配置项（按 key 排序后的防御性副本，避免外部直接改内部状态）。
     *
     * @return 不可被外部修改内部状态的配置项列表
     */
    @Override
    public List<ConfigItem> listConfigs() {
        List<ConfigItem> items = new ArrayList<>(configs.size());
        for (ConfigItem item : configs.values()) {
            items.add(copyOf(item));
        }
        items.sort(Comparator.comparing(ConfigItem::getKey));
        return items;
    }

    /** 该 key 是否为本管理器支持的配置项 */
    @Override
    public boolean supports(String key) {
        return configs.containsKey(key);
    }

    /**
     * 更新一个配置项的值并热应用到运行时，随后把全量配置持久化到 overlay。
     *
     * @param key   配置项 key
     * @param value 新值（会 trim；push.enabled 统一归一化为 true/false）
     * @return 本次变更事件（含新旧值与应用模式），便于调用方记录或审计
     * @throws IllegalArgumentException  配置项不存在或值非法（如非正数 / 非布尔）
     * @throws UnsupportedOperationException 配置项不允许热更新（当前内置项均允许）
     */
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
        // 布尔开关归一化，避免 "TRUE"/" 1" 之类的歧义值进入持久化
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

    /** 把当前全量配置按键序写入 overlay 文件，保证重启后状态一致 */
    private void persistOverlay() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (ConfigItem item : listConfigs()) {
            snapshot.put(item.getKey(), item.getValue());
        }
        overlayStore.save(snapshot);
    }

    /**
     * 更新前的值校验：以 "Millis" 结尾的配置必须是正数；push.enabled 必须是 true/false。
     *
     * @throws IllegalArgumentException 校验不通过时抛出
     */
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

    /**
     * 注册一个内置配置项，统一为可热更新、非敏感。
     *
     * @param key         配置项 key
     * @param value       当前值
     * @param defaultValue 默认值
     * @param description 中文说明，展示给 Admin 管理端
     */
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

    /** 构造 ConfigItem 的防御性副本，防止外部直接修改内部配置对象 */
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