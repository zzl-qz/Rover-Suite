package com.rover.gateway.core.route;

import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 14:22:00
 * Description: Gateway 路由规则
 */
@Data
public class RouteConfig {

    private String id;
    /** 网关入口前缀 */
    private String businessPrefix;
    /** 静态模式下的目标地址 */
    private String targetUrl;
    /** 动态模式下的服务名 */
    private String serviceName;
    /** 动态模式下的分组，可空 */
    private String group;
    /** 去前缀规则 */
    private String stripPrefix;
}
