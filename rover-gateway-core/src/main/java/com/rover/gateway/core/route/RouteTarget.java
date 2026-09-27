package com.rover.gateway.core.route;

/**
 * Author: Daylight
 * Created: 2026-09-27 14:10:00
 * Description: 动态路由的一个「版本目标」：注册中心里的 (serviceName, group) 及它在路由内的分流权重
 *
 * <p>权重是相对值，不要求各家加总等于某个常数；{@code weight = 0} 表示「注册但不接流」，
 * 这是灰度停推的实现方式——保留 target 就保留了它的配置与指标维度，不必删了再加。
 *
 * @param serviceName 目标服务名
 * @param group       目标分组，在本项目里当版本用；为空表示默认组
 * @param weight      分流权重，0 表示不接流
 */
public record RouteTarget(String serviceName, String group, int weight) {

    /** 未显式给权重时的默认值，与静态上游 `|weight` 的默认口径一致。 */
    public static final int DEFAULT_WEIGHT = 100;

    /** 权重上限：给出上界，避免管理口写出会让分流区间数量级失控的值。 */
    public static final int MAX_WEIGHT = 10_000;

    public RouteTarget {
        serviceName = trimToNull(serviceName);
        group = trimToNull(group);
    }

    /** 用默认权重构造一个目标。 */
    public static RouteTarget of(String serviceName, String group) {
        return new RouteTarget(serviceName, group, DEFAULT_WEIGHT);
    }

    /** 负载均衡计数与指标隔离用的集群键：{@code service@group}。 */
    public String clusterKey() {
        return serviceName + "@" + (group == null ? "" : group);
    }

    /** 展示用标签：{@code service@group}；无分组时只显示服务名。 */
    public String label() {
        return group == null ? serviceName : serviceName + "@" + group;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
