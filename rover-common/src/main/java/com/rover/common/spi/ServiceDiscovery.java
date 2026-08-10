package com.rover.common.spi;

import java.util.List;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 定义按服务名发现可用实例的能力
 *
 * 这个接口是什么：面向调用方的服务发现抽象。
 * 核心职责：屏蔽「注册中心协议 / 本地缓存」等实现细节，向网关与消费者提供
 * 按服务名拿到实例列表的统一入口。
 * 被谁用：网关路由转发、客户端调用前的寻址；实现方为注册中心客户端或本地缓存。
 */
public interface ServiceDiscovery {

    /**
     * 按服务名查询当前可用实例。
     *
     * @param serviceName 服务名
     * @return 实例列表，无可用实例时返回空列表(非 null)
     */
    List<Instance> getInstances(String serviceName);
}
