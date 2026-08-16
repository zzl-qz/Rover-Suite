package com.rover.admin.config;

import com.rover.common.constants.NameserverConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Author: Daylight
 * Created: 2026-08-09 13:45:00
 * Description: Admin 连接 Gateway / Nameserver 管理口的地址配置
 */
@ConfigurationProperties(prefix = "rover.admin")
public class AdminProperties {

    /** Gateway 管理口地址，默认端口 80（与 GatewayConfig.DEFAULT_PORT 保持一致） */
    private String gatewayUrl = "http://127.0.0.1:80";
    private String nameserverManageUrl = NameserverConstants.DEFAULT_MANAGE_URL;
    /** 管理口鉴权 token，调用 Gateway/Nameserver 管理口时通过 X-Rover-Admin-Token 携带；空表示不鉴权 */
    private String adminToken;

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
}
