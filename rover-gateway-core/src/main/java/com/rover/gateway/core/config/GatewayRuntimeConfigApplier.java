/**
 * 作者：Daylight
 * 创建时间：2026-08-08 14:59:00
 * 描述：负责将 Gateway 配置变更应用到运行时组件
 */
package com.rover.gateway.core.config;

import com.rover.common.config.ConfigChangeEvent;

public class GatewayRuntimeConfigApplier {

    /**
     * 应用 Gateway 配置变更，后续在这里刷新路由、过滤器、负载均衡和超时配置。
     */
    public void apply(ConfigChangeEvent event) {
    }
}
