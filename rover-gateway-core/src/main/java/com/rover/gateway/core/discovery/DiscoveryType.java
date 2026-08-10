package com.rover.gateway.core.discovery;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:20:00
 * Description: 上游发现模式
 */
public enum DiscoveryType {

    /** 使用路由里的 targetUrl，不连 Nameserver */
    STATIC,

    /** 通过 Nameserver 订阅 serviceName 对应实例 */
    NAMESERVER;

    public static DiscoveryType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return STATIC;
        }
        return DiscoveryType.valueOf(raw.trim().toUpperCase());
    }
}
