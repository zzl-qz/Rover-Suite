package com.rover.gateway.core.route;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 14:22:00
 * Description: Gateway 路由规则：静态上游 targetUrl/targetUrls，动态上游 targets（同服务多版本 + 权重）
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

    /**
     * 动态上游的版本目标列表；与静态地址互斥。
     *
     * <p>列表顺序参与分流计算：权重区间按此顺序首尾相接，因此放量时把权重从靠前的版本
     * 挪给紧邻的靠后版本，只增不减（见 {@link WeightedTargetRouter}）。
     */
    private List<RouteTarget> targets = new ArrayList<>();

    /** 灰度粘性键所在的请求头；为空表示直接用客户端 IP 兜底。 */
    private String stickyHeader;

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
