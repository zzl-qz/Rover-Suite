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
 *
 * 这个类是什么：RuntimeConfigManager 的 Gateway 实现，管理三项可热更配置。
 * 核心职责：注册 filter.enabled、loadbalance.strategy、request.timeoutMillis；
 * 支持 seed/loadOverlay/reapplyAll 启动流程；updateConfig 校验、apply、持久化 overlay。
 * 被谁用：GatewayHttpServer 创建并绑定 GatewayRuntime；GatewayManageApi 查询/更新配置。
 */
@Slf4j
public class GatewayRuntimeConfigManager implements RuntimeConfigManager {

    /** 默认 overlay 文件路径。 */
    public static final Path DEFAULT_OVERLAY = Path.of("config", "gateway-runtime.overlay.json");

    /** 内存中的配置项表，key -> ConfigItem。 */
    private final Map<String, ConfigItem> configs = new ConcurrentHashMap<>();

    /** 配置变更应用到 GatewayRuntime 的桥接器。 */
    private final GatewayRuntimeConfigApplier applier;

    /** overlay 持久化存储。 */
    private final RuntimeConfigOverlayStore overlayStore;

    /** 使用默认 applier 和 overlay 路径构造，并注册三项默认配置。 */
    public GatewayRuntimeConfigManager() {
        this(new GatewayRuntimeConfigApplier(), new RuntimeConfigOverlayStore(DEFAULT_OVERLAY));
    }

    /**
     * @param applier 配置应用器
     */
    public GatewayRuntimeConfigManager(GatewayRuntimeConfigApplier applier) {
        this(applier, new RuntimeConfigOverlayStore(DEFAULT_OVERLAY));
    }

    /**
     * @param applier      配置应用器
     * @param overlayStore overlay 存储
     */
    public GatewayRuntimeConfigManager(
            GatewayRuntimeConfigApplier applier, RuntimeConfigOverlayStore overlayStore) {
        this.applier = applier;
        this.overlayStore = overlayStore;
        addConfig("gateway.filter.enabled", "true", "true", "网关过滤器总开关");
        addConfig(
                "gateway.loadbalance.strategy",
                "round_robin",
                "round_robin",
                "负载均衡：round_robin/random/weighted_round_robin/ip_hash/least_connections，或自定义类名/SPI名");
        addConfig("gateway.request.timeoutMillis", "30000", "30000", "网关请求超时时间");
    }

    /** @return 配置应用器，用于 bind GatewayRuntime */
    public GatewayRuntimeConfigApplier getApplier() {
        return applier;
    }

    /** @return overlay 存储，供 status 接口展示路径 */
    public RuntimeConfigOverlayStore getOverlayStore() {
        return overlayStore;
    }

    /**
     * 启动时用真实 YAML 灌初值，不触发 apply。
     *
     * @param key   配置 key
     * @param value 配置值
     */
    public void seed(String key, String value) {
        ConfigItem item = configs.get(key);
        if (item != null && value != null) {
            item.setValue(value);
        }
    }

    /** YAML 之后叠 overlay 文件，仍不 apply。 */
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

    /** applier bind 之后，把当前值真正打进运行时。 */
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
     * 返回当前组件暴露给管理端的配置项副本。
     *
     * @return 按 key 排序的配置项列表
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

    /**
     * 判断当前组件是否管理指定配置项。
     *
     * @param key 配置项 key
     * @return true 表示该 key 由本组件接管
     */
    @Override
    public boolean supports(String key) {
        return configs.containsKey(key);
    }

    /**
     * 更新指定配置项：校验 → 写内存 → apply → 落盘 overlay。
     *
     * @param key   配置项 key
     * @param value 新的配置值
     * @return 封装了新旧值与生效方式的变更事件
     * @throws IllegalArgumentException     未知 key 或值非法
     * @throws UnsupportedOperationException 配置项不支持热更新
     */
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
        // loadbalance.strategy 可能是自定义类全名，不能强行 toLowerCase
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

    /** 把当前全部配置快照写入 overlay 文件。 */
    private void persistOverlay() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (ConfigItem item : listConfigs()) {
            snapshot.put(item.getKey(), item.getValue());
        }
        overlayStore.save(snapshot);
    }

    /** 按 key 校验配置值合法性。 */
    private void validate(String key, String value) {
        if ("gateway.loadbalance.strategy".equals(key)) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("loadbalance.strategy 不能为空");
            }
            // 具体合法性在 GatewayRuntime.applyLoadBalanceStrategy → Factory 里校验
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

    /** 注册一项可热更配置。 */
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

    /** 深拷贝 ConfigItem，避免外部修改内部状态。 */
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
