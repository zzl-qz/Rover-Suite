package com.rover.nameserver.core.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.common.codec.RoverMessageCodecSupport;
import com.rover.common.model.ServiceInstance;
import com.rover.common.protocol.RegisterRequest;
import com.rover.common.protocol.RoverMessage;
import com.rover.common.protocol.ServicePushBody;
import com.rover.nameserver.core.cluster.NameserverGeneration;
import com.rover.nameserver.core.metrics.NameserverMetricsRegistry;
import com.rover.nameserver.core.registration.RegistrationOwner;
import com.rover.nameserver.core.registration.RegistrationService;
import com.rover.nameserver.core.registry.InMemoryServiceRegistry;
import com.rover.nameserver.core.registry.RegistrySnapshot;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PushServiceGroupFilterTest {

    @Test
    void groupSubscriberReceivesOnlyItsGroupWhileWildcardReceivesAll() {
        SubscriptionManager subscriptions = new SubscriptionManager();
        PushService pushService = new PushService(subscriptions, true, NameserverGeneration.processLocal());
        EmbeddedChannel blue = new EmbeddedChannel();
        EmbeddedChannel red = new EmbeddedChannel();
        EmbeddedChannel all = new EmbeddedChannel();
        subscriptions.subscribe("orders", "blue", blue);
        subscriptions.subscribe("orders", "red", red);
        subscriptions.subscribe("orders", null, all);

        RegistrySnapshot snapshot = RegistrySnapshot.of(
                "orders",
                "blue",
                3,
                List.of(instance("blue-1", "blue"), instance("red-1", "red")));
        pushService.pushSnapshot(snapshot);

        ServicePushBody blueBody = readPush(blue);
        assertEquals("blue", blueBody.getGroup());
        assertEquals(List.of("blue-1"), ids(blueBody));
        assertNull(blue.readOutbound());

        ServicePushBody allBody = readPush(all);
        assertNull(allBody.getGroup());
        assertEquals(List.of("blue-1", "red-1"), ids(allBody));

        assertNull(red.readOutbound());
        blue.finishAndReleaseAll();
        red.finishAndReleaseAll();
        all.finishAndReleaseAll();
    }

    @Test
    void registerPushUsesRealSnapshotAndStillFiltersBySubscription() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        SubscriptionManager subscriptions = new SubscriptionManager();
        PushService pushService = new PushService(subscriptions, true, NameserverGeneration.processLocal());
        RegistrationService registration =
                new RegistrationService(registry, pushService, new NameserverMetricsRegistry());
        EmbeddedChannel blue = new EmbeddedChannel();
        EmbeddedChannel red = new EmbeddedChannel();
        EmbeddedChannel all = new EmbeddedChannel();
        subscriptions.subscribe("orders", "blue", blue);
        subscriptions.subscribe("orders", "red", red);
        subscriptions.subscribe("orders", null, all);

        registration.register(request("red-1", "red"), RegistrationOwner.http("red-session"));
        assertNull(blue.readOutbound());
        assertEquals(List.of("red-1"), ids(readPush(red)));
        assertEquals(List.of("red-1"), ids(readPush(all)));

        registration.register(request("blue-1", "blue"), RegistrationOwner.http("blue-session"));
        ServicePushBody blueBody = readPush(blue);
        assertEquals("blue", blueBody.getGroup());
        assertEquals(List.of("blue-1"), ids(blueBody));
        assertEquals(2, registry.query("orders", null, false).size());
        assertNull(red.readOutbound());
        ServicePushBody allBody = readPush(all);
        assertNull(allBody.getGroup());
        assertEquals(Set.of("red-1", "blue-1"), Set.copyOf(ids(allBody)));

        blue.finishAndReleaseAll();
        red.finishAndReleaseAll();
        all.finishAndReleaseAll();
    }

    private static RegisterRequest request(String id, String group) {
        RegisterRequest request = new RegisterRequest();
        request.setServiceName("orders");
        request.setInstanceId(id);
        request.setHost("127.0.0.1");
        request.setPort(8080);
        request.setGroup(group);
        request.setEphemeral(true);
        return request;
    }

    private static ServicePushBody readPush(EmbeddedChannel channel) {
        Object outbound = channel.readOutbound();
        assertTrue(outbound instanceof RoverMessage);
        return RoverMessageCodecSupport.decodeBody((RoverMessage) outbound, ServicePushBody.class);
    }

    private static List<String> ids(ServicePushBody body) {
        return body.getInstances().stream().map(ServiceInstance::getInstanceId).toList();
    }

    private static ServiceInstance instance(String id, String group) {
        ServiceInstance instance = new ServiceInstance();
        instance.setServiceName("orders");
        instance.setInstanceId(id);
        instance.setHost("127.0.0.1");
        instance.setPort(8080);
        instance.setGroup(group);
        return instance;
    }
}
