package com.rover.nameserver.core.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rover.common.protocol.RegisterRequest;
import com.rover.nameserver.core.cluster.NameserverGeneration;
import com.rover.nameserver.core.metrics.NameserverMetricsRegistry;
import com.rover.nameserver.core.push.PushService;
import com.rover.nameserver.core.push.SubscriptionManager;
import com.rover.nameserver.core.registry.InMemoryServiceRegistry;
import com.rover.nameserver.core.registry.RegistrySnapshot;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RegistrationServiceTest {

    @Test
    void idempotentRegisterDoesNotPushAgain() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        AtomicInteger pushes = new AtomicInteger();
        PushService pushService = new PushService(
                new SubscriptionManager(),
                true,
                NameserverGeneration.processLocal(),
                new NameserverMetricsRegistry()) {
            @Override
            public void pushSnapshot(RegistrySnapshot snapshot) {
                pushes.incrementAndGet();
            }
        };
        RegistrationService service =
                new RegistrationService(registry, pushService, new NameserverMetricsRegistry());
        RegistrationOwner owner = RegistrationOwner.http("session-one");
        RegisterRequest request = request();

        service.register(request, owner);
        service.register(request, owner);

        assertEquals(1, pushes.get());
        assertEquals(1L, registry.revisionOf("svc"));
    }

    private static RegisterRequest request() {
        RegisterRequest request = new RegisterRequest();
        request.setServiceName("svc");
        request.setInstanceId("one");
        request.setHost("127.0.0.1");
        request.setPort(8080);
        request.setEphemeral(true);
        return request;
    }
}
