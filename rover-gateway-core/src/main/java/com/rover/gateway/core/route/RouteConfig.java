package com.rover.gateway.core.route;

import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 14:22:00
 * Description: Gateway 路由规则
 *
 * 这个类是什么：单条路由的配置模型，描述入口前缀到后端的映射关系。
 * 核心职责：承载 businessPrefix、静态 targetUrl、动态 serviceName/group、stripPrefix 等字段。
 * 被谁用：RouteMatcher 匹配；RouteAndProxyFilter 解析目标；GatewayManageApi 增删改查。
 */
@Data
public class RouteConfig {

    /** 路由唯一标识，管理口删除时可按 id 匹配 */
    private String id;

    /** 网关入口前缀，如 /api/user，请求路径以此开头即命中 */
    private String businessPrefix;

    /** 静态模式下的目标地址，如 http://127.0.0.1:8080 */
    private String targetUrl;

    /** 动态模式下的服务名，配合 Nameserver 发现实例 */
    private String serviceName;

    /** 动态模式下的分组，可空表示默认组 */
    private String group;

    /** 转发前去前缀规则，如 /api/user 会被剥掉再拼到后端 */
    private String stripPrefix;
}
