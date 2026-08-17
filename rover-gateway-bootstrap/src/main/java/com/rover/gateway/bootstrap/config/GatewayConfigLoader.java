package com.rover.gateway.bootstrap.config;

import com.rover.common.config.YamlConfigLoader;
import com.rover.common.config.ConfigFiles;

/**
 * Author: Daylight
 * Created: 2026-08-04 09:42:00
 * Description: Gateway 配置加载入口，委托 YamlConfigLoader 读取 rover-gateway.yml
 */
public class GatewayConfigLoader {

    private static final String CONFIG_FILE = ConfigFiles.GATEWAY_YAML;

    private final YamlConfigLoader<GatewayConfig> delegate =
            new YamlConfigLoader<>(CONFIG_FILE, GatewayConfig.class, GatewayConfig::validate);

    public GatewayConfig load() {
        return delegate.load();
    }
}
