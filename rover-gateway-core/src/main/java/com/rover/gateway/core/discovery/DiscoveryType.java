package com.rover.gateway.core.discovery;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:20:00
 * Description: 上游发现模式
 *
 * 这个枚举是什么：Gateway 选择后端地址的两种策略标识。
 * 核心职责：STATIC 用路由 targetUrl；NAMESERVER 通过注册中心查实例并负载均衡。
 * 被谁用：DiscoverySettings、GatewayRuntime、RouteAndProxyFilter 判断转发逻辑。
 */
public enum DiscoveryType {

    /** 使用路由里的 targetUrl，不连 Nameserver */
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
