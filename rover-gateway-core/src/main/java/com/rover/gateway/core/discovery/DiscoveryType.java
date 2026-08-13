package com.rover.gateway.core.discovery;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:20:00
 * Description: 上游发现模式
 *
 * 这个枚举是什么：Gateway 选择后端地址的两种策略标识。
 * 核心职责：STATIC 用 targetUrl/targetUrls（多 IP 同样走 LB）；
 * NAMESERVER 通过注册中心查实例并负载均衡。
 */
public enum DiscoveryType {

    /** 静态上游列表，不连 Nameserver；多 IP 走 LoadBalancer */
    STATIC,

    /** 通过 Nameserver 订阅 serviceName 对应实例 */
    NAMESERVER;

    /**
     * 从配置字符串解析发现模式，空值默认 STATIC。
     *
     * @param raw 配置原始值，如 "STATIC" / "NAMESERVER"
     * @return 对应的 DiscoveryType
     * @throws IllegalArgumentException raw 不是合法枚举名
     */
    public static DiscoveryType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return STATIC;
        }
        return DiscoveryType.valueOf(raw.trim().toUpperCase());
    }
}
