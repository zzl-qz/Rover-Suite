package com.rover.common.spi.plugin;

import java.util.List;

/**
 * 插件声明给 Gateway Admin 的一个可热更新配置项。
 * key 是插件内字段名；Gateway 会将其注册为 gateway.plugin.{namespace}.{key}。
 */
public record PluginConfigProperty(
        String key,
        String defaultValue,
        String description,
        List<String> options,
        boolean sensitive) {

    public PluginConfigProperty {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("插件配置 key 不能为空");
        }
        defaultValue = defaultValue == null ? "" : defaultValue;
        description = description == null ? "" : description;
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** 快速声明非敏感、自由输入的配置项。 */
    public PluginConfigProperty(String key, String defaultValue, String description) {
        this(key, defaultValue, description, List.of(), false);
    }
}
