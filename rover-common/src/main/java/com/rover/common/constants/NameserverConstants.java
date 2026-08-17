package com.rover.common.constants;

/**
 * Author: Daylight
 * Created: 2026-08-16 20:00:00
 * Description: Nameserver 默认地址与端口常量。供 nameserver 服务端/客户端、gateway、admin
 * 在未显式配置时作为兜底默认值统一引用，避免同一端口散落多处、改一处漏一处。
 * 各模块仍可通过各自的配置文件覆盖。
 */
public final class NameserverConstants {

    /** 工具类不允许实例化 */
    private NameserverConstants() {
    }

    /** 默认主机地址（本地联调兜底，生产环境应显式配置） */
    public static final String DEFAULT_HOST = NetworkConstants.IPV4_LOOPBACK;

    /** 服务端默认监听所有 IPv4 网卡 */
    public static final String DEFAULT_BIND_HOST = NetworkConstants.IPV4_ANY;

    /** Nameserver TCP 注册发现端口 */
    public static final int DEFAULT_PORT = 8888;

    /** Nameserver HTTP 管理口端口，Admin 调这里 */
    public static final int DEFAULT_MANAGE_PORT = 8889;

    /** 默认健康检查周期 */
    public static final long DEFAULT_HEALTH_CHECK_INTERVAL_MILLIS = 5_000L;

    /** 默认心跳超时 */
    public static final long DEFAULT_HEARTBEAT_TIMEOUT_MILLIS = 15_000L;

    /** 默认临时实例过期时间 */
    public static final long DEFAULT_INSTANCE_EXPIRE_MILLIS = 30_000L;

    /** 默认地址 host:port */
    public static final String DEFAULT_ADDRESS = DEFAULT_HOST + ":" + DEFAULT_PORT;

    /** 默认管理口 URL */
    public static final String DEFAULT_MANAGE_URL =
            HttpConstants.SCHEME_HTTP + "://" + DEFAULT_HOST + ":" + DEFAULT_MANAGE_PORT;
}
