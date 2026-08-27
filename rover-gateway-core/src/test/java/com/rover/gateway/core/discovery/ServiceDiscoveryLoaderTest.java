package com.rover.gateway.core.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.common.spi.discovery.ServiceDiscovery;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

class ServiceDiscoveryLoaderTest {

    @Test
    void staticTypeUsesNoop() {
        DiscoverySettings settings = new DiscoverySettings();
        settings.setType(DiscoveryType.STATIC);
        assertInstanceOf(NoopServiceDiscovery.class, ServiceDiscoveryLoader.load(settings));
        assertInstanceOf(NoopServiceDiscovery.class, ServiceDiscoveryLoader.load(null));
    }

    @Test
    void nameserverFactoryIsOnClasspath() {
        boolean found = false;
        for (ServiceDiscoveryFactory factory : ServiceLoader.load(ServiceDiscoveryFactory.class)) {
            if (factory.type() == DiscoveryType.NAMESERVER) {
                found = true;
                ServiceDiscovery discovery = factory.create(new DiscoverySettings());
                discovery.close();
                break;
            }
        }
        assertTrue(found);
    }

    @Test
    void nameserverTypeCreatesNameserverDiscovery() {
        DiscoverySettings settings = new DiscoverySettings();
        settings.setType(DiscoveryType.NAMESERVER);
        ServiceDiscovery discovery = ServiceDiscoveryLoader.load(settings);
        try {
            assertInstanceOf(NameserverServiceDiscovery.class, discovery);
        } finally {
            discovery.close();
        }
    }

    @Test
    void missingAdapterFailsFast() {
        DiscoverySettings settings = new DiscoverySettings();
        settings.setType(DiscoveryType.NACOS);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> ServiceDiscoveryLoader.load(settings));
        assertTrue(error.getMessage().contains("NACOS"));
    }

    @Test
    void redisIsNotADiscoveryType() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> DiscoveryType.from("REDIS"));
        assertTrue(error.getMessage().contains("static / nameserver / nacos"));
    }

    @Test
    void usesServiceDiscoveryMatchesFactoryPath() {
        assertEquals(false, DiscoveryType.STATIC.usesServiceDiscovery());
        assertEquals(true, DiscoveryType.NAMESERVER.usesServiceDiscovery());
        assertEquals(true, DiscoveryType.NACOS.usesServiceDiscovery());
    }
}
