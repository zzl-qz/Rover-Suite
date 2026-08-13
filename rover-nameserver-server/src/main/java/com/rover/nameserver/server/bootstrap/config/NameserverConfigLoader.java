package com.rover.nameserver.server.bootstrap.config;

import com.rover.common.config.YamlConfigLoader;

/**
 * Nameserver 配置加载入口。
 *
 * 这个类是什么：rover-nameserver.yml 的加载门面，委托给通用 YamlConfigLoader。
 * 核心职责：固定文件名与目标类型，按「外部 config/ 文件 → classpath → 默认」加载。
 * 被谁用：{@link com.rover.nameserver.server.bootstrap.NameserverApplication}。
 */
public class NameserverConfigLoader {

    private static final String CONFIG_FILE = "rover-nameserver.yml";

    private final YamlConfigLoader<NameserverConfig> delegate =
            new YamlConfigLoader<>(CONFIG_FILE, NameserverConfig.class, null);

    public NameserverConfig load() {
        return delegate.load();
    }
}
