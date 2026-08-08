/**
 * 作者：Daylight
 * 创建时间：2026-08-08 14:22:00
 * 描述：描述 Gateway 路由规则配置
 */
package com.rover.gateway.core.route;

import lombok.Data;

@Data
public class RouteConfig {

    private String id;
    private String businessPrefix;
    private String targetUrl;
    private String stripPrefix;
}
