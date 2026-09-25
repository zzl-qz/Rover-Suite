package com.rover.agent.core.snapshot;

import java.util.Locale;

/** Gateway 上游发现模式；无法识别的上报值按 {@link #UNKNOWN} 处理。 */
public enum DiscoveryMode {

    /** 静态上游列表 */
    STATIC,

    /** Rover 自带注册中心 */
    NAMESERVER,

    /** Nacos 服务发现 */
    NACOS,

    /** 读取失败或上报值无法识别 */
    UNKNOWN;

    /** 解析 Gateway 上报的发现模式字符串。 */
    public static DiscoveryMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return STATIC;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}