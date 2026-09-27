package com.rover.admin.config;

import com.rover.common.constants.NameserverConstants;
import com.rover.common.constants.GatewayConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Author: Daylight
 * Created: 2026-08-09 13:45:00
 * Description: Admin 连接 Gateway / Nameserver 管理口的地址配置
 */
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
    /** 落盘记录保留天数，超期由定时任务清理；默认 30 天 */
    private int logRetentionDays = 30;
    /** 异步写入普通(遥测)队列容量；满则 best-effort 丢弃；默认 8192 */
    private int logQueueCapacity = 8192;
    /** 异步写入高优(诊断证据)队列容量；满则短暂阻塞等待，尽量不丢；默认 16384 */
    private int logCriticalCapacity = 16384;

    public String getGatewayUrl() {
        return gatewayUrl;
    }

    public void setGatewayUrl(String gatewayUrl) {
        this.gatewayUrl = gatewayUrl;
    }

    public String getNameserverManageUrl() {
        return nameserverManageUrl;
    }

    public void setNameserverManageUrl(String nameserverManageUrl) {
        this.nameserverManageUrl = nameserverManageUrl;
    }

    public String getAdminToken() {
        return adminToken;
    }

    public void setAdminToken(String adminToken) {
        this.adminToken = adminToken;
    }

    public String getLogStorePath() {
        return logStorePath;
    }

    public void setLogStorePath(String logStorePath) {
        this.logStorePath = logStorePath;
    }

    public int getLogRetentionDays() {
        return logRetentionDays;
    }

    public void setLogRetentionDays(int logRetentionDays) {
        this.logRetentionDays = logRetentionDays;
    }

    public int getLogQueueCapacity() {
        return logQueueCapacity;
    }

    public void setLogQueueCapacity(int logQueueCapacity) {
        this.logQueueCapacity = logQueueCapacity;
    }

    public int getLogCriticalCapacity() {
        return logCriticalCapacity;
    }

    public void setLogCriticalCapacity(int logCriticalCapacity) {
        this.logCriticalCapacity = logCriticalCapacity;
    }
}
