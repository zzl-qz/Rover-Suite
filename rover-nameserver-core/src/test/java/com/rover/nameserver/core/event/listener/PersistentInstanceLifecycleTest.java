package com.rover.nameserver.core.event.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.rover.common.protocol.AckMode;
import com.rover.common.protocol.RegisterRequest;
import com.rover.nameserver.core.cluster.NameserverGeneration;
import com.rover.nameserver.core.consistency.DefaultWriteAckPolicy;
import com.rover.nameserver.core.event.model.ChannelInactiveEvent;
import com.rover.nameserver.core.event.model.RegisterEvent;
import com.rover.nameserver.core.event.support.NameserverChannelSupport;
import com.rover.nameserver.core.event.support.NameserverServices;
import com.rover.nameserver.core.metrics.NameserverMetricsRegistry;
import com.rover.nameserver.core.push.PushService;
import com.rover.nameserver.core.push.SubscriptionManager;
import com.rover.nameserver.core.registry.InMemoryServiceRegistry;
import com.rover.nameserver.core.server.NameserverServerOptions;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

class PersistentInstanceLifecycleTest {

    @Test
    void persistentRegistrationIsNotOwnedByChannel() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        SubscriptionManager subscriptions = new SubscriptionManager();
        NameserverGeneration generation = NameserverGeneration.processLocal();
        NameserverMetricsRegistry metrics = new NameserverMetricsRegistry();
        PushService pushService = new PushService(subscriptions, true, generation, metrics);
        NameserverServerOptions options = NameserverServerOptions.builder()
                .writeAckMode(AckMode.SINGLE)
                .replicationFactor(1)
                .build();
        NameserverServices services = new NameserverServices(
                registry,
                subscriptions,
                pushService,
                new DefaultWriteAckPolicy(),
                options,
                generation,
                metrics);
        EmbeddedChannel channel = new EmbeddedChannel();
        RegisterEvent event = new RegisterEvent();
        event.setChannel(channel);
        event.setAckMode(AckMode.SINGLE);
        event.setRequest(request(false));

        new RegisterListener(services).onEvent(event);
        new ChannelInactiveListener(services).onEvent(ChannelInactiveEvent.of(channel));

        assertEquals(1, registry.query("svc", null, false).size());
        assertNull(channel.attr(NameserverChannelSupport.BOUND_INSTANCES).get());
        channel.finishAndReleaseAll();
    }

    private static RegisterRequest request(boolean ephemeral) {
        RegisterRequest request = new RegisterRequest();
        request.setServiceName("svc");
        request.setInstanceId("instance#with-separator");
        request.setHost("127.0.0.1");
        request.setPort(8080);
        request.setEphemeral(ephemeral);
        return request;
    }
}
