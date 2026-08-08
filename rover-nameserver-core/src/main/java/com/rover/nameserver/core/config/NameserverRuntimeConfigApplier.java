/**
 * 作者：Daylight
 * 创建时间：2026-08-08 14:59:00
 * 描述：负责将 Nameserver 配置变更应用到运行时组件
 */
package com.rover.nameserver.core.config;

import com.rover.common.config.ConfigChangeEvent;

public class NameserverRuntimeConfigApplier {

    /**
     * 应用 Nameserver 配置变更，后续在这里刷新健康检查、心跳和推送相关组件。
     */
    public void apply(ConfigChangeEvent event) {
    }
}
