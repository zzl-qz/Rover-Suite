package com.rover.gateway.core.config;

import com.rover.common.config.AbstractRuntimeConfigManager;
import com.rover.common.config.ConfigChangeEvent;
import com.rover.common.config.ConfigFiles;
import com.rover.common.config.ConfigValues;
import com.rover.common.config.RuntimeConfigOverlayStore;
import com.rover.common.spi.loadbalance.BuiltinLoadBalanceStrategy;
import com.rover.common.spi.loadbalance.LoadBalancer;
import com.rover.common.constants.RoverComponent;
import lombok.Getter;

import java.nio.file.Path;
import java.util.List;

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
        addConfig(GatewayRuntimeConfigKeys.TRACE_ENABLED,
                ConfigValues.TRUE, ConfigValues.TRUE, "请求链路时间线总开关", ConfigValues.BOOLEAN_OPTIONS);
        String slowThreshold = Long.toString(GatewayDefaults.TRACE_SLOW_THRESHOLD_MILLIS);
        addConfig(GatewayRuntimeConfigKeys.TRACE_SLOW_THRESHOLD_MILLIS,
                slowThreshold, slowThreshold, "慢请求阈值（毫秒），超过即记录时间线",
                List.of("50", "100", "200", "500"));
        String sampleRate = Double.toString(GatewayDefaults.TRACE_SAMPLE_RATE);
        addConfig(GatewayRuntimeConfigKeys.TRACE_SAMPLE_RATE,
                sampleRate, sampleRate, "时间线采样率 0~1，0 表示只记慢请求，1 表示全量",
                List.of("0", "0.01", "0.1", "0.5", "1"));
    }

    @Override
    protected void applyChange(ConfigChangeEvent event) {
        applier.apply(event);
    }

    @Override
    protected void validate(String key, String value) {
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
    }

    @Override
    protected String normalize(String key, String value) {
        // loadbalance.strategy 可能是自定义类全名，不能强行 toLowerCase
        if (GatewayRuntimeConfigKeys.BOOLEAN_KEYS.contains(key)) {
            return ConfigValues.normalizeBoolean(value);
        }
        return value;
    }
}
