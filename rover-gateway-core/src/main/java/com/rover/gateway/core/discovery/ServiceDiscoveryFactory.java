package com.rover.gateway.core.discovery;

import com.rover.common.spi.discovery.ServiceDiscovery;

/**
 * 按配置创建服务发现实现的扩展工厂。
 * <p>Gateway core 只依赖该接口；具体注册中心通过 ServiceLoader 提供实现。</p>
 */
public interface ServiceDiscoveryFactory {

    /** 返回该工厂支持的发现类型。 */
    DiscoveryType type();

    /** 根据 Gateway 发现配置创建客户端，客户端尚未启动。 */
    ServiceDiscovery create(DiscoverySettings settings);
}
