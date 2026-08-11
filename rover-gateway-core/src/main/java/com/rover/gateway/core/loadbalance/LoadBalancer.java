package com.rover.gateway.core.loadbalance;

import com.rover.common.model.ServiceInstance;
import java.util.List;

/**
 * Author: Daylight
 * Created: 2026-08-08 14:22:00
 * Description: 定义 Gateway 选择后端服务实例的负载均衡契约
 *
 * 这个接口是什么：动态发现模式下从多个实例里挑一个的 SPI。
 * 核心职责：给定 serviceName 和实例列表，返回一个目标实例；无可用实例时返回 null。
 * 被谁用：RouteAndProxyFilter 动态模式选上游；GatewayRuntime 按策略热切换实现。
 */
public interface LoadBalancer {

    /**
     * 从服务实例列表中选择一个目标实例。
     *
     * @param serviceName 服务名，部分策略按服务名维护计数器
     * @param instances   候选实例列表
     * @return 选中的实例；列表为空或 null 时返回 null
     */
    ServiceInstance choose(String serviceName, List<ServiceInstance> instances);
}
