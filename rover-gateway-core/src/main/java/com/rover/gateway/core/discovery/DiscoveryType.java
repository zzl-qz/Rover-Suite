package com.rover.gateway.core.discovery;

import java.util.Locale;

/**
 * Author: Daylight
 * Created: 2026-08-03 09:30:00
 * Description: 上游发现模式。static 用 YAML 列表；其它走 ServiceDiscoveryLoader 工厂。
 */
public enum DiscoveryType {

    /** 静态上游列表，多 IP 走 LoadBalancer */
    STATIC,

    /** 组件自带注册中心 */
    NAMESERVER,

    /** Nacos 服务发现；需要额外加入 Nacos adapter。 */
    NACOS;

    /** 从配置字符串解析发现模式，空值默认 STATIC。 */
    public static DiscoveryType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return STATIC;
        }
        String type = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return DiscoveryType.valueOf(type);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "discovery.type 只支持 static / nameserver / nacos，当前是: " + raw.trim());
        }
    }

    /** 走 ServiceDiscovery 按 serviceName 选实例，不是 YAML 静态列表。 */
    public boolean usesServiceDiscovery() {
        return this != STATIC;
    }
}
