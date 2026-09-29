package com.rover.agent.core.port;

/**
 * 路由上的一个版本目标：服务名 + 分组（版本）+ 权重。
 *
 * @param serviceName 目标服务名
 * @param group       版本分组，如 {@code v2}；为空表示不限分组
 * @param weight      流量权重；{@code 0} 是「不再给这个版本分流」的原语，不是删除目标
 */
public record RouteControlTarget(String serviceName, String group, int weight) {

    public RouteControlTarget {
        serviceName = serviceName == null ? "" : serviceName.trim();
        group = group == null ? "" : group.trim();
    }

    /** 展示用标签：{@code service@group}；无分组时只显示服务名。 */
    public String label() {
        return group.isBlank() ? serviceName : serviceName + "@" + group;
    }
}
