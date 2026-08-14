package com.rover.gateway.bootstrap.config;

import com.rover.common.config.YamlConfigLoader;

/**
 * Gateway 配置加载入口。
 */
public class GatewayConfigLoader {

    private static final String CONFIG_FILE = "rover-gateway.yml";

    private final YamlConfigLoader<GatewayConfig> delegate =
            new YamlConfigLoader<>(CONFIG_FILE, GatewayConfig.class, GatewayConfig::validate);

    public GatewayConfig load() {
        return delegate.load();
    }
}
