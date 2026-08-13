package com.rover.common.config;

/**
 * 配置变更应用器：把 {@link ConfigChangeEvent} 应用到具体运行时组件。
 *
 * 这个类是什么：RuntimeConfigManager 与具体运行时之间的解耦接口。
 * 核心职责：接收配置变更事件，按 key 分发到对应组件的 setter。
 * 被谁用：GatewayRuntimeConfigApplier / NameserverRuntimeConfigApplier 实现；
 * AbstractRuntimeConfigManager 在 updateConfig / reapplyAll 时调用。
 */
@FunctionalInterface
public interface ConfigApplier {

    /**
     * 应用一条配置变更事件。
     *
     * @param event 配置变更事件，含 key / 新旧值 / 生效方式
     */
    void apply(ConfigChangeEvent event);
}
