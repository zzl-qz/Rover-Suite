package com.rover.nameserver.starter.autoconfigure;

import java.util.HashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:10:00
 * Description: rover.nameserver 配置项
 */
@Data
@ConfigurationProperties(prefix = "rover.nameserver")
public class RoverNameserverProperties {

    /** 是否启用自动注册 */
    private boolean enabled = true;

    /** Nameserver 地址，host:port */
    private String address = "127.0.0.1:8888";

    /** 服务名；不填可用 spring.application.name */
    private String serviceName;

    /** 实例 ID；不填则用 host:port */
    private String instanceId;

    /** 对外注册的 host；不填自动探测 */
    private String host;

    /** 对外注册的端口；不填则用 server.port */
    private Integer port;

    /** 分组 */
    private String group;

    /** 机房/可用区 */
    private String zone;

    /** 权重 */
    private int weight = 100;

    /** 临时实例 */
    private boolean ephemeral = true;

    /** 扩展元数据 */
    private Map<String, String> metadata = new HashMap<>();

    /** 连接超时 */
    private int connectTimeoutMs = 3000;

    /** 单次请求超时 */
    private int requestTimeoutMs = 3000;

    /** 心跳间隔 */
    private long heartbeatIntervalMs = 5000L;

    /** 自动重连 */
    private boolean autoReconnect = true;

    /** 重连间隔 */
    private long reconnectIntervalMs = 3000L;

    /** 首次注册失败后的重试间隔 */
    private long registerRetryIntervalMs = 5000L;
}
