package com.rover.gateway.core.discovery;

import com.rover.common.model.ServiceInstance;
import java.util.List;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:20:00
 * Description: 静态模式占位，不连注册中心
 *
 * 这个类是什么：ServiceDiscovery 的空实现，STATIC 模式下使用。
 * 核心职责：所有方法都是 no-op 或返回空列表，表示上游地址完全来自路由 targetUrl。
 * 被谁用：GatewayHttpServer 在 discovery.type=STATIC 时创建。
 */
public class NoopServiceDiscovery implements ServiceDiscovery {

    /** 静态模式无需启动任何发现客户端。 */
    @Override
    public void start() {
        // static 模式什么都不做
    }

    /**
     * 静态模式不查注册中心，始终返回空列表。
     *
     * @param serviceName 服务名（忽略）
     * @param group       分组（忽略）
     * @return 空列表
     */
    @Override
    public List<ServiceInstance> getInstances(String serviceName, String group) {
        return List.of();
    }

    /** 静态模式无需释放资源。 */
    @Override
    public void close() {
        // no-op
    }
}
