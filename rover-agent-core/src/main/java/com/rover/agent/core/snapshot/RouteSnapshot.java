package com.rover.agent.core.snapshot;

/**
 * 一条 Gateway 路由的只读快照。
 *
 * @param routeId         路由 ID
 * @param businessPrefix  业务前缀，参与路径匹配
 * @param serviceName     目标服务名；静态上游时为空
 * @param group            目标服务分组；为空表示不限分组
 * @param targetUrl       静态上游地址；动态服务时为空
 * @param observedAtMillis 取证时刻
 */
public record RouteSnapshot(String routeId, String businessPrefix, String serviceName, String group,
                            String targetUrl, long observedAtMillis) { }