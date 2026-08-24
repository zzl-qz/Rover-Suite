package com.rover.gateway.core.config;

import com.rover.common.config.AbstractRuntimeConfigManager;
import com.rover.common.config.ConfigChangeEvent;
import com.rover.common.config.ConfigFiles;
import com.rover.common.config.ConfigItem;
import com.rover.common.config.ConfigValues;
import com.rover.common.config.RuntimeConfigOverlayStore;
import com.rover.common.spi.loadbalance.BuiltinLoadBalanceStrategy;
import com.rover.common.spi.loadbalance.LoadBalancer;
import com.rover.common.spi.plugin.ConfigurablePlugin;
import com.rover.common.spi.plugin.PluginConfigProperty;
import com.rover.common.constants.RoverComponent;
import com.rover.gateway.core.filter.ratelimit.RateLimitSettings;
import lombok.Getter;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Author: Daylight
 * Created: 2026-08-09 09:55:00
 * Description: Gateway 侧 RuntimeConfigManager 实现，把变更事件桥接给 Applier
 */
@Getter
public class GatewayRuntimeConfigManager extends AbstractRuntimeConfigManager {

    /** 默认 overlay 文件路径。 */
    public static final Path DEFAULT_OVERLAY = ConfigFiles.GATEWAY_RUNTIME_OVERLAY;

    /** 配置变更应用到 GatewayRuntime 的桥接器。 */
    private final GatewayRuntimeConfigApplier applier;

    /** 当前已装配插件的配置处理器；插件重建时原子替换其内容。 */
    private final Map<String, PluginConfigBinding> pluginBindings = new ConcurrentHashMap<>();
    /** 曾注册的插件键，用于隐藏已卸载插件的旧配置，同时保留其 overlay 值。 */
    private final Set<String> knownPluginConfigKeys = ConcurrentHashMap.newKeySet();

    /** 使用默认 applier 和 overlay 路径构造。 */
    public GatewayRuntimeConfigManager() {
        this(new GatewayRuntimeConfigApplier());
    }

    /** 使用指定 applier（便于测试注入）。 */
    public GatewayRuntimeConfigManager(GatewayRuntimeConfigApplier applier) {
        this(applier, new RuntimeConfigOverlayStore(DEFAULT_OVERLAY));
    }

    /** 完整构造：注入 applier 与 overlay 存储，并注册内置配置项。 */
    public GatewayRuntimeConfigManager(
            GatewayRuntimeConfigApplier applier, RuntimeConfigOverlayStore overlayStore) {
        super(overlayStore, RoverComponent.GATEWAY.displayName());
        this.applier = applier;
        addConfig(
                GatewayRuntimeConfigKeys.LOAD_BALANCE_STRATEGY,
                LoadBalancer.ROUND_ROBIN,
                LoadBalancer.ROUND_ROBIN,
                "负载均衡：" + String.join("/", BuiltinLoadBalanceStrategy.configNames())
                        + "，或自定义类名/SPI名",
                BuiltinLoadBalanceStrategy.configNames());
        String requestTimeout = Integer.toString(GatewayDefaults.REQUEST_TIMEOUT_MILLIS);
        addConfig(GatewayRuntimeConfigKeys.REQUEST_TIMEOUT_MILLIS,
                requestTimeout, requestTimeout, "网关请求超时时间（毫秒）",
                List.of("1000", "3000", "5000", "10000", "30000", "60000"));
        addConfig(GatewayRuntimeConfigKeys.METRICS_ENABLED,
                ConfigValues.TRUE, ConfigValues.TRUE, "指标采集总开关，false 一键降级",
                ConfigValues.BOOLEAN_OPTIONS);
        String metricsWindow = Integer.toString(GatewayDefaults.METRICS_WINDOW_SECONDS);
        addConfig(GatewayRuntimeConfigKeys.METRICS_WINDOW_SECONDS,
                metricsWindow, metricsWindow, "指标滑动窗口时长（秒），上限 300",
                List.of("60", "120", "300"));
        addConfig(GatewayRuntimeConfigKeys.FILTER_ENABLED,
                ConfigValues.TRUE, ConfigValues.TRUE, "网关过滤器总开关", ConfigValues.BOOLEAN_OPTIONS);
        addConfig(GatewayRuntimeConfigKeys.RATE_LIMIT_ENABLED,
                ConfigValues.FALSE, ConfigValues.FALSE,
                "内置本地限流开关；按 Gateway 实例独立计数", ConfigValues.BOOLEAN_OPTIONS);
        addConfig(GatewayRuntimeConfigKeys.RATE_LIMIT_ALGORITHM,
                RateLimitSettings.TOKEN_BUCKET, RateLimitSettings.TOKEN_BUCKET,
                "内置限流算法", List.of(RateLimitSettings.TOKEN_BUCKET, RateLimitSettings.SLIDING_WINDOW));
        addConfig(GatewayRuntimeConfigKeys.RATE_LIMIT_KEY,
                RateLimitSettings.PATH, RateLimitSettings.PATH,
                "限流配额维度：global=整台 Gateway，path=按请求路径", List.of(RateLimitSettings.GLOBAL, RateLimitSettings.PATH));
        addConfig(GatewayRuntimeConfigKeys.RATE_LIMIT_PERMITS_PER_SECOND,
                "1000", "1000", "令牌桶每秒补充令牌数", List.of("100", "500", "1000", "5000"));
        addConfig(GatewayRuntimeConfigKeys.RATE_LIMIT_BURST,
                "2000", "2000", "令牌桶最大突发容量", List.of("100", "500", "1000", "2000", "5000"));
        addConfig(GatewayRuntimeConfigKeys.RATE_LIMIT_LIMIT,
                "1000", "1000", "滑动窗口内最多允许的请求数", List.of("100", "500", "1000", "5000"));
        addConfig(GatewayRuntimeConfigKeys.RATE_LIMIT_WINDOW_SECONDS,
                "1", "1", "滑动窗口时长（秒）", List.of("1", "10", "60"));
        addConfig(GatewayRuntimeConfigKeys.TRACE_ENABLED,
                ConfigValues.TRUE, ConfigValues.TRUE, "请求链路时间线总开关", ConfigValues.BOOLEAN_OPTIONS);
        String slowThreshold = Long.toString(GatewayDefaults.TRACE_SLOW_THRESHOLD_MILLIS);
        addConfig(GatewayRuntimeConfigKeys.TRACE_SLOW_THRESHOLD_MILLIS,
                slowThreshold, slowThreshold, "慢请求阈值（毫秒），超过即记录时间线",
                List.of("50", "100", "200", "500"));
        String sampleRate = Double.toString(GatewayDefaults.TRACE_SAMPLE_RATE);
        addConfig(GatewayRuntimeConfigKeys.TRACE_SAMPLE_RATE,
                sampleRate, sampleRate,
                "采样率：0=只记慢请求，1=全量记录；也可填 0~1 之间小数做抽样",
                List.of("0", "1"));
    }

    @Override
    protected void applyChange(ConfigChangeEvent event) {
        PluginConfigBinding binding = pluginBindings.get(event.getKey());
        if (binding != null) {
            binding.plugin().applyConfig(binding.propertyKey(), event.getNewValue());
            return;
        }
        applier.apply(event);
    }

    /**
     * 用当前过滤器链和当前 LoadBalancer 的可配置插件刷新绑定。
     * 已保存的同名配置不会被默认值覆盖；插件暂时未装配时，配置从 Admin 隐藏但仍留在 overlay。
     */
    public synchronized void replacePluginConfigs(Collection<? extends ConfigurablePlugin> plugins) {
        pluginBindings.clear();
        if (plugins == null) {
            return;
        }
        for (ConfigurablePlugin plugin : plugins) {
            if (plugin == null) {
                continue;
            }
            registerPluginConfigs(plugin);
        }
    }

    @Override
    public List<ConfigItem> listConfigs() {
        return super.listConfigs().stream()
                .filter(item -> !GatewayRuntimeConfigKeys.LOAD_BALANCE_STRATEGY.equals(item.getKey()))
                .filter(item -> !knownPluginConfigKeys.contains(item.getKey())
                        || pluginBindings.containsKey(item.getKey()))
                .toList();
    }

    @Override
    public synchronized ConfigChangeEvent updateConfig(String key, String value) {
        if (GatewayRuntimeConfigKeys.LOAD_BALANCE_STRATEGY.equals(key)) {
            throw new UnsupportedOperationException(
                    "gateway.loadbalance.strategy 属于启动/插件装配配置，请在 rover-gateway.yml 中修改并重启 Gateway");
        }
        if (knownPluginConfigKeys.contains(key) && !pluginBindings.containsKey(key)) {
            throw new IllegalStateException("插件当前未装配，不能更新配置：" + key);
        }
        return super.updateConfig(key, value);
    }

    @Override
    protected void validate(String key, String value) {
        PluginConfigBinding binding = pluginBindings.get(key);
        if (binding != null) {
            binding.plugin().validateConfig(binding.propertyKey(), value);
            return;
        }
        if (knownPluginConfigKeys.contains(key)) {
            throw new IllegalStateException("插件当前未装配，不能更新配置：" + key);
        }
        if (GatewayRuntimeConfigKeys.LOAD_BALANCE_STRATEGY.equals(key)) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("loadbalance.strategy 不能为空");
            }
        }
        if (GatewayRuntimeConfigKeys.REQUEST_TIMEOUT_MILLIS.equals(key)) {
            long timeout = Long.parseLong(value);
            if (timeout <= 0) {
                throw new IllegalArgumentException("timeoutMillis 必须大于 0");
            }
        }
        if (GatewayRuntimeConfigKeys.BOOLEAN_KEYS.contains(key) && !ConfigValues.isBoolean(value)) {
            throw new IllegalArgumentException(key + " 仅支持 true/false");
        }
        if (GatewayRuntimeConfigKeys.METRICS_WINDOW_SECONDS.equals(key)) {
            int window = Integer.parseInt(value.trim());
            if (window <= 0) {
                throw new IllegalArgumentException("metrics.windowSeconds 必须大于 0");
            }
        }
        if (GatewayRuntimeConfigKeys.TRACE_SLOW_THRESHOLD_MILLIS.equals(key)) {
            long threshold = Long.parseLong(value.trim());
            if (threshold <= 0) {
                throw new IllegalArgumentException("trace.slowThresholdMillis 必须大于 0");
            }
        }
        if (GatewayRuntimeConfigKeys.TRACE_SAMPLE_RATE.equals(key)) {
            double rate = Double.parseDouble(value.trim());
            if (rate < 0 || rate > 1) {
                throw new IllegalArgumentException("trace.sampleRate 必须在 0~1 之间");
            }
        }
        if (GatewayRuntimeConfigKeys.RATE_LIMIT_ALGORITHM.equals(key)
                && !RateLimitSettings.TOKEN_BUCKET.equals(value)
                && !RateLimitSettings.SLIDING_WINDOW.equals(value)) {
            throw new IllegalArgumentException("rateLimit.algorithm 仅支持 token_bucket/sliding_window");
        }
        if (GatewayRuntimeConfigKeys.RATE_LIMIT_KEY.equals(key)
                && !RateLimitSettings.GLOBAL.equals(value)
                && !RateLimitSettings.PATH.equals(value)) {
            throw new IllegalArgumentException("rateLimit.key 仅支持 global/path");
        }
        if (GatewayRuntimeConfigKeys.RATE_LIMIT_PERMITS_PER_SECOND.equals(key)
                || GatewayRuntimeConfigKeys.RATE_LIMIT_BURST.equals(key)
                || GatewayRuntimeConfigKeys.RATE_LIMIT_LIMIT.equals(key)) {
            if (Long.parseLong(value.trim()) <= 0) {
                throw new IllegalArgumentException(key + " 必须大于 0");
            }
        }
        if (GatewayRuntimeConfigKeys.RATE_LIMIT_WINDOW_SECONDS.equals(key)) {
            int seconds = Integer.parseInt(value.trim());
            if (seconds <= 0 || seconds > 3600) {
                throw new IllegalArgumentException("rateLimit.windowSeconds 必须在 1~3600 之间");
            }
        }
    }

    @Override
    protected String normalize(String key, String value) {
        // loadbalance.strategy 可能是自定义类全名，不能强行 toLowerCase
        if (GatewayRuntimeConfigKeys.BOOLEAN_KEYS.contains(key)) {
            return ConfigValues.normalizeBoolean(value);
        }
        return value;
    }

    @Override
    protected boolean runtimeOverlayKey(String key) {
        return !GatewayRuntimeConfigKeys.LOAD_BALANCE_STRATEGY.equals(key);
    }

    private void registerPluginConfigs(ConfigurablePlugin plugin) {
        String namespace = requirePluginSegment(plugin.configNamespace(), "namespace");
        List<PluginConfigProperty> properties = plugin.configProperties();
        if (properties == null) {
            return;
        }
        for (PluginConfigProperty property : properties) {
            if (property == null) {
                continue;
            }
            String propertyKey = requirePluginSegment(property.key(), "property key");
            String configKey = "gateway.plugin." + namespace + "." + propertyKey;
            PluginConfigBinding binding = new PluginConfigBinding(plugin, propertyKey);
            PluginConfigBinding previous = pluginBindings.putIfAbsent(configKey, binding);
            if (previous != null && previous.plugin() != plugin) {
                throw new IllegalArgumentException("重复的插件配置键：" + configKey);
            }
            knownPluginConfigKeys.add(configKey);
            addConfigIfAbsent(
                    configKey,
                    property.defaultValue(),
                    property.description(),
                    property.options(),
                    property.sensitive());
            String current = currentValue(configKey);
            binding.plugin().validateConfig(binding.propertyKey(), current);
            binding.plugin().applyConfig(binding.propertyKey(), current);
        }
    }

    private static String requirePluginSegment(String value, String label) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("插件 " + label + " 仅支持字母、数字、点、下划线和连字符");
        }
        return value;
    }

    /** 一个完整 Gateway 配置键与插件字段的映射；私有状态不扩散为公共模型。 */
    private record PluginConfigBinding(ConfigurablePlugin plugin, String propertyKey) {
    }
}
