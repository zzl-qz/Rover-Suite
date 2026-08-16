package com.rover.nameserver.bootstrap.config;

import com.rover.common.constants.NameserverConstants;
import com.rover.common.protocol.AckMode;
import java.util.ArrayList;
import java.util.List;

import com.rover.nameserver.bootstrap.NameserverApplication;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 15:13:00
 * Description: rover-nameserver.yml 的强类型映射根对象，按 rover.nameserver 组织配置块并提供端口默认值兜底与 ACK 模式解析
 */
@Data
public class NameserverConfig {

    /** 服务未配置端口时的默认 TCP 端口 */
    private static final int DEFAULT_PORT = NameserverConstants.DEFAULT_PORT;

    /** 根配置块：rover.nameserver.* */
    private RoverProperties rover = new RoverProperties();

    /**
     * 返回有效 TCP 端口：配置值 > 0 用之，否则回退默认端口 8888。
     *
     * @return 实际监听端口
     */
    public int getPortOrDefault() {
        int port = rover.getNameserver().getPort();
        if (port <= 0) {
            return DEFAULT_PORT;
        }
        return port;
    }

    /**
     * 把配置的 ACK 模式字符串（SINGLE/HALF/ALL）解析为枚举。
     *
     * @return 解析后的 AckMode；未知名由 {@link AckMode#fromName} 语义处理
     */
    public AckMode resolveWriteAckMode() {
        return AckMode.fromName(rover.getNameserver().getWriteAckMode());
    }

    /** rover 顶层配置块 */
    @Data
    public static class RoverProperties {

        /** nameserver 子配置块 */
        private NameserverProperties nameserver = new NameserverProperties();
    }

    /** nameserver 服务端配置块：TCP 端口、管理口、超时参数、推送开关、ACK 与集群 */
    @Data
    public static class NameserverProperties {

        /** TCP 注册发现端口 */
        private int port = DEFAULT_PORT;
        /** TCP 监听地址，默认 0.0.0.0 */
        private String bindHost = "0.0.0.0";
        /** HTTP 管理口，Admin 调这里 */
        private int managePort = NameserverConstants.DEFAULT_MANAGE_PORT;
        /** HTTP 管理口监听地址，默认 0.0.0.0 */
        private String manageBindHost = "0.0.0.0";
        /** 集群协议鉴权 token（注册/注销/订阅）；空表示不鉴权 */
        private String token;
        /** HTTP 管理口鉴权 token；空表示不鉴权 */
        private String adminToken;
        /** 心跳超时时间(ms)：超过则标记实例不健康 */
        private long heartbeatTimeoutMillis = 15000L;
        /** 健康检查扫描间隔(ms) */
        private long healthCheckIntervalMillis = 5000L;
        /** 临时实例过期时间(ms)：超过则剔除 */
        private long instanceExpireMillis = 30000L;
        /** 服务变更推送总开关 */
        private boolean pushEnabled = true;

        // SINGLE / HALF / ALL，当前单机部署统一按本地确认处理
        private String writeAckMode = AckMode.SINGLE.name();

        // 默认不允许客户端自行调整 ACK 强度
        private boolean allowClientAckOverride = false;

        /** 集群配置块（当前预留） */
        private ClusterProperties cluster = new ClusterProperties();
    }

    /** 集群配置块：当前关闭，为后续多节点部署预留 */
    @Data
    public static class ClusterProperties {

        // 当前关闭，为后续集群部署预留
        private boolean enabled = false;
        /** 本节点 ID（响应体 nodeId 标注用） */
        private String nodeId;
        /** 集群成员节点地址列表（预留） */
        private List<String> nodes = new ArrayList<>();
        /** 副本数（预留） */
        private int replicationFactor = 1;
    }
}
