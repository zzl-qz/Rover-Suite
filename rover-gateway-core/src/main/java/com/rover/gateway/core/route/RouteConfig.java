package com.rover.gateway.core.route;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 14:22:00
 * Description: Gateway 路由规则
 *
 * 静态上游：targetUrl（单机兼容）和/或 targetUrls（多机，可带 |weight）。
 * 动态上游：serviceName + group，走 Nameserver。
 */
@Data
public class RouteConfig {

    /** 路由唯一标识，管理口删除时可按 id 匹配 */
    private String id;

    /** 网关入口前缀，如 /api/user，请求路径以此开头即命中 */
    private String businessPrefix;

    /** 静态单上游（兼容旧配置），可与 targetUrls 一起用 */
    private String targetUrl;

    /**
     * 静态多上游。元素支持：
     * http://127.0.0.1:8081
     * http://127.0.0.1:8082|200  （竖线后是权重）
     */
    private List<String> targetUrls = new ArrayList<>();

    /** 动态模式下的服务名，配合 Nameserver 发现实例 */
    private String serviceName;

    /** 动态模式下的分组，可空表示默认组 */
    private String group;

    /** 转发前去前缀规则，如 /api/user 会被剥掉再拼到后端 */
    private String stripPrefix;
}
