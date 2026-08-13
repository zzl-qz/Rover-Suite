package com.rover.gateway.core.config;

import com.rover.common.config.AbstractRuntimeConfigManager;
import com.rover.common.config.ConfigChangeEvent;
import com.rover.common.config.RuntimeConfigOverlayStore;
import java.nio.file.Path;

/**
 * Gateway 侧 RuntimeConfigManager 实现。
 *
 * 这个类是什么：管理网关三项可热更新配置（过滤器开关、负载均衡策略、请求超时）。
 * 核心职责：注册内置配置项；校验/归一化规则；把变更事件桥接给 GatewayRuntimeConfigApplier。
 * 被谁用：GatewayHttpServer 装配；GatewayManageApi 查询/更新配置。
 */
public class GatewayRuntimeConfigManager extends AbstractRuntimeConfigManager {

    /** 默认 overlay 文件路径。 */
    public static final Path DEFAULT_OVERLAY = Path.of("config", "gateway-runtime.overlay.json");

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
        super(overlayStore, "Gateway");
        this.applier = applier;
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

    @Override
    protected void applyChange(ConfigChangeEvent event) {
        applier.apply(event);
    }

    @Override
    protected void validate(String key, String value) {
        if ("gateway.loadbalance.strategy".equals(key)) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("loadbalance.strategy 不能为空");
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

    @Override
    protected String normalize(String key, String value) {
        // loadbalance.strategy 可能是自定义类全名，不能强行 toLowerCase
        if ("gateway.filter.enabled".equals(key)) {
            return Boolean.parseBoolean(value) ? "true" : "false";
        }
        return value;
    }
}
