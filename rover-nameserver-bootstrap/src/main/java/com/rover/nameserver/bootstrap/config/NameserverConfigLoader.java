package com.rover.nameserver.bootstrap.config;

import com.rover.common.config.YamlConfigLoader;

/**
 * Author: Daylight
 * Created: 2026-08-04 14:20:00
 * Description: rover-nameserver.yml 配置加载门面，固定文件名与目标类型，委托 YamlConfigLoader 按外部 config/ 优先、classpath 兜底加载
 */
public class NameserverConfigLoader {

    private static final String CONFIG_FILE = "rover-nameserver.yml";

    private final YamlConfigLoader<NameserverConfig> delegate =
            new YamlConfigLoader<>(CONFIG_FILE, NameserverConfig.class, null);

    public NameserverConfig load() {
        return delegate.load();
    }
}
