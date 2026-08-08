package com.rover.gateway.core.route;

import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 14:22:00
 * Description: 描述 Gateway 静态路由规则，包含业务前缀、目标地址和去前缀规则
 */
@Data
public class RouteConfig {

    private String id;
    private String businessPrefix;
    private String targetUrl;
    private String stripPrefix;
}
