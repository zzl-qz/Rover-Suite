package com.rover.common.config;

/**
 * Author: Daylight
 * Created: 2026-08-04 10:30:00
 * Description: 配置变更应用器：把 {@link ConfigChangeEvent} 应用到具体运行时组件
 */
@FunctionalInterface
public interface ConfigApplier {

    /** 应用一条配置变更事件。 */
    void apply(ConfigChangeEvent event);
}
