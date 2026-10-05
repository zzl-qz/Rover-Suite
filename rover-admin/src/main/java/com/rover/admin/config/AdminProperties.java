package com.rover.admin.config;

import com.rover.common.constants.NameserverConstants;
import com.rover.common.constants.GatewayConstants;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Author: Daylight
 * Created: 2026-08-09 13:45:00
 * Description: Admin 管理口连接与运行配置。
 */
@Data
@ConfigurationProperties(prefix = AdminProperties.PREFIX)
public class AdminProperties {

    public static final String PREFIX = "rover.admin";

    /** Gateway 管理口地址，默认端口 80（与 GatewayConfig.DEFAULT_PORT 保持一致） */
    private String gatewayUrl = GatewayConstants.DEFAULT_URL;
    private String nameserverManageUrl = NameserverConstants.DEFAULT_MANAGE_URL;
    /** 管理口鉴权 token，调用 Gateway/Nameserver 管理口时通过 X-Rover-Admin-Token 携带；空表示不鉴权 */
    private String adminToken;

    /** 落盘记录库（H2 嵌入式）文件路径，不含 .mv.db 后缀；默认 ./rover-logs/rover */
    private String logStorePath = "./rover-logs/rover";
    /** 落盘记录保留天数（诊断证据类：配置变更/健康翻转/审计），超期清理；默认 30 天 */
    private int logRetentionDays = 30;
    /** 遥测类记录保留天数（指标采样/慢链路/心跳），超期清理；默认 3 天，避免膨胀 */
    private int logTelemetryRetentionDays = 3;
    /** 遥测采集间隔（秒）：周期拉取 Gateway/Nameserver 指标与链路写入落盘库；默认 30 */
    private int logCollectIntervalSeconds = 30;
    /** 异步写入普通(遥测)队列容量；满则 best-effort 丢弃；默认 8192 */
    private int logQueueCapacity = 8192;
    /** 异步写入高优(诊断证据)队列容量；满则短暂阻塞等待，尽量不丢；默认 16384 */
    private int logCriticalCapacity = 16384;
}
