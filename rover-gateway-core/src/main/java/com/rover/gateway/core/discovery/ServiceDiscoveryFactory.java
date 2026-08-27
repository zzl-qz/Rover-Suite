package com.rover.gateway.core.discovery;

import com.rover.common.spi.discovery.ServiceDiscovery;

/**
 * 按配置创建服务发现实现的工厂。
 * Nameserver 工厂在 core 里；Nacos 等可选 adapter 自己挂一条 SPI。
 */
public interface ServiceDiscoveryFactory {

    /** 返回该工厂支持的发现类型。 */
    DiscoveryType type();

    /** 根据 Gateway 发现配置创建客户端，客户端尚未启动。 */
    ServiceDiscovery create(DiscoverySettings settings);
}
