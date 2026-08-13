package com.rover.gateway.bootstrap.config;

import com.rover.common.config.YamlConfigLoader;

/**
 * Gateway 配置加载入口。
 *
 * 这个类是什么：rover-gateway.yml 的加载门面，委托给通用 YamlConfigLoader。
 * 核心职责：固定文件名与目标类型，加载后触发 GatewayConfig.validate 校验。
 * 被谁用：{@link com.rover.gateway.bootstrap.GatewayApplication}。
 */
public class GatewayConfigLoader {

    private static final String CONFIG_FILE = "rover-gateway.yml";

    private final YamlConfigLoader<GatewayConfig> delegate =
            new YamlConfigLoader<>(CONFIG_FILE, GatewayConfig.class, GatewayConfig::validate);

    public GatewayConfig load() {
        return delegate.load();
    }
}
