package com.rover.gateway.core.route;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 14:22:00
 * Description: Gateway 路由规则：静态上游 targetUrl/targetUrls，动态上游 serviceName + group
 */
@Data
public class RouteConfig {

    /** 路由唯一标识，管理口删除时可按 id 匹配 */
    private String id;

    /** 网关入口前缀，如 /api/user，请求路径以此开头即命中 */
    private String businessPrefix;

    /** 静态单上游（兼容旧配置），可与 targetUrls 一起用 */
    private String targetUrl;

    /** 静态多上游，元素支持 http://host:port 或 http://host:port|weight。 */
    private List<String> targetUrls = new ArrayList<>();

    /** 动态模式下的服务名，配合注册中心发现实例 */
    private String serviceName;

    /** 动态模式下的分组，可空表示默认组 */
    private String group;

    /** 转发前去前缀规则，如 /api/user 会被剥掉再拼到后端 */
    private String stripPrefix;

    /** 除根路径外去掉尾斜杠，避免 /api 和 /api/ 当成两条规则。 */
    public static String normalizePrefix(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
