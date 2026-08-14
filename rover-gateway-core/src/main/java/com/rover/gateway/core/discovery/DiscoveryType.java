package com.rover.gateway.core.discovery;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:20:00
 * Description: 上游发现模式（静态/动态（后续会支持多种注册中心））
 * // todo 后续会支持多种注册中心
 */
public enum DiscoveryType {

    /** 静态上游列表，多 IP 走 LoadBalancer */
    STATIC,


    /** 静态上游列表，多 IP 走 LoadBalancer */
    NAMESERVER, // 组件自带注册中心

    NACOS, // nacos（后续支持）

    REDIS; // redis（后续支持）

    /**
     * 从配置字符串解析发现模式，空值默认 STATIC。
     */
    public static DiscoveryType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return STATIC;
        }
        return DiscoveryType.valueOf(raw.trim().toUpperCase());
    }
}
