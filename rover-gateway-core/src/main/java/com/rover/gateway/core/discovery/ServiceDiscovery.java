package com.rover.gateway.core.discovery;

import com.rover.common.model.ServiceInstance;
import java.util.List;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:20:00
 * Description: 上游服务实例发现
 *
 * 这个接口是什么：Gateway 查后端实例的统一契约，支持静态和动态两种模式。
 * 核心职责：启动订阅/连接；按 serviceName+group 返回实例列表；
 * 路由热更新后补订新服务；关闭时释放资源。
 * 被谁用：RouteAndProxyFilter 动态模式下选上游；GatewayHttpServer 按配置创建实现。
 */
public interface ServiceDiscovery extends AutoCloseable {

    /** 启动发现客户端，建立连接并开始订阅。 */
    void start();

    /**
     * 获取指定服务的可用实例列表。
     *
     * @param serviceName 服务名
     * @param group       分组，可为 null
     * @return 实例列表，无可用实例时返回空列表
     */
    List<ServiceInstance> getInstances(String serviceName, String group);

    /**
     * 路由热更新后补订新服务；静态发现默认空操作。
     *
     * @param serviceName 服务名
     * @param group       分组，可为 null
     */
    default void ensureWatch(String serviceName, String group) {
    }

    /** 关闭发现客户端，释放连接和后台任务。 */
    @Override
    void close();
}
