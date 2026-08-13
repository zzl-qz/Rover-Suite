package com.rover.common.spi.discovery;

import com.rover.common.model.ServiceInstance;
import java.util.List;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 上游服务实例发现（跨模块契约）
 *
 * 这个接口是什么：调用方查后端实例的统一 SPI，屏蔽 Nameserver / Nacos / 静态等实现。
 * 核心职责：启动订阅；按 serviceName+group 取实例；路由热更新后补订；关闭释放资源。
 * 被谁用：Gateway 动态转发；以后 Nacos 适配模块也实现这一份，不要再在 gateway 里另起一套。
 */
public interface ServiceDiscovery extends AutoCloseable {

    /** 启动发现客户端，建立连接并开始订阅。 */
    void start();

    /**
     * 获取指定服务的可用实例列表。
     *
     * @param serviceName 服务名
     * @param group       分组，可为 null
     * @return 实例列表，无可用实例时返回空列表（非 null）
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
