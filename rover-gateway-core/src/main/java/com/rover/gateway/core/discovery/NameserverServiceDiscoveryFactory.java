package com.rover.gateway.core.discovery;

import com.rover.common.spi.discovery.ServiceDiscovery;

/** Nameserver 发现的工厂；core 自带，启动时一定能被 ServiceLoader 找到。 */
public final class NameserverServiceDiscoveryFactory implements ServiceDiscoveryFactory {

    @Override
    public DiscoveryType type() {
        return DiscoveryType.NAMESERVER;
    }

    @Override
    public ServiceDiscovery create(DiscoverySettings settings) {
        return new NameserverServiceDiscovery(settings);
    }
}
