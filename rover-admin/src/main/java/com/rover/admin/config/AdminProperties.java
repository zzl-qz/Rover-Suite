package com.rover.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Author: Daylight
 * Created: 2026-08-09 13:45:00
 * Description: Admin 连接 Gateway / Nameserver 管理口的地址配置
 */
@ConfigurationProperties(prefix = "rover.admin")
public class AdminProperties {

    private String gatewayUrl = "http://127.0.0.1:8080";
    private String nameserverManageUrl = "http://127.0.0.1:8889";

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
}
