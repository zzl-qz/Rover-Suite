package com.rover.gateway.core.discovery;

/**
 * Author: Daylight
 * Created: 2026-08-03 09:30:00
 * Description: 上游发现模式：STATIC 静态列表；NAMESERVER 组件自带注册中心；NACOS/REDIS 预留
 */
public enum DiscoveryType {

    /** 静态上游列表，多 IP 走 LoadBalancer */
    STATIC,

    /** 组件自带注册中心 */
    NAMESERVER,

    /** Nacos 服务发现；需要额外加入 Nacos adapter。 */
    NACOS,

    /** @DL 预留：接入 Redis 发现实现后启用。 */
    REDIS;

    /** 从配置字符串解析发现模式，空值默认 STATIC。 */
    public static DiscoveryType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return STATIC;
        }
        return DiscoveryType.valueOf(raw.trim().toUpperCase());
    }
}
