package com.rover.gateway.adapter.nacos;

import com.rover.gateway.core.discovery.DiscoverySettings;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.gateway.core.discovery.ServiceDiscoveryFactory;
import com.rover.common.spi.discovery.ServiceDiscovery;

/**
 * Nacos 服务发现的 ServiceLoader 工厂入口。
 * <p>Gateway 只有在 classpath 中包含本模块且 discovery.type=nacos 时才会使用它。</p>
 */
public final class NacosServiceDiscoveryFactory implements ServiceDiscoveryFactory {

    /** 声明本工厂负责 Nacos。 */
    @Override
    public DiscoveryType type() {
        return DiscoveryType.NACOS;
    }

    /** 创建 Nacos 服务发现客户端；真正连接在 {@link NacosServiceDiscovery#start()} 时建立。 */
    @Override
    public ServiceDiscovery create(DiscoverySettings settings) {
        return new NacosServiceDiscovery(settings);
    }
}
