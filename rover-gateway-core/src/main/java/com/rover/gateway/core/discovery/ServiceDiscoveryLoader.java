package com.rover.gateway.core.discovery;

import com.rover.common.spi.discovery.ServiceDiscovery;
import java.util.ServiceLoader;

/**
 * 按 discovery.type 创建发现客户端。
 * static 用空实现；其它一律找 ServiceLoader 工厂，core 自带 Nameserver，Nacos 在可选模块里。
 */
public final class ServiceDiscoveryLoader {

    private ServiceDiscoveryLoader() {
    }

    public static ServiceDiscovery load(DiscoverySettings settings) {
        DiscoverySettings safe = settings == null ? DiscoverySettings.staticDefaults() : settings;
        DiscoveryType type = safe.getType() == null ? DiscoveryType.STATIC : safe.getType();
        if (!type.usesServiceDiscovery()) {
            return new NoopServiceDiscovery();
        }
        for (ServiceDiscoveryFactory factory : ServiceLoader.load(ServiceDiscoveryFactory.class)) {
            if (factory.type() == type) {
                return factory.create(safe);
            }
        }
        throw new IllegalStateException(
                "未找到 discovery.type=" + type + " 的适配器，请确认对应模块已加入 classpath");
    }
}
