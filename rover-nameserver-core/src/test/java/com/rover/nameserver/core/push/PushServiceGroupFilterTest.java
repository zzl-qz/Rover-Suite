package com.rover.nameserver.core.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

        // 每组各自收到「按自己过滤后」的名单：订了 blue 的只收 blue，订了通配的收全量
        Map<String, List<String>> bluePushes = drainByGroup(blue);
        assertEquals(Set.of("blue"), bluePushes.keySet());
        assertEquals(List.of("blue-1"), bluePushes.get("blue"));

        Map<String, List<String>> redPushes = drainByGroup(red);
        assertEquals(Set.of("red"), redPushes.keySet());
        assertEquals(List.of("red-1"), redPushes.get("red"));

        Map<String, List<String>> allPushes = drainByGroup(all);
        assertEquals(Set.of(""), allPushes.keySet(), "通配订阅的推送不带组名");
        assertEquals(List.of("blue-1", "red-1"), allPushes.get(""));

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

        // 本组一台实例都没有时，推的是空名单而不是不推：
        // 组内最后一台下线是真实事件，订阅者必须能收到「这组空了」才会停止转发。
        Map<String, List<String>> bluePushes = drainByGroup(blue);
        assertEquals(Set.of("blue"), bluePushes.keySet());
        assertTrue(bluePushes.get("blue").isEmpty(), "blue 组没有实例，应收到空名单");
        assertEquals(List.of("red-1"), drainByGroup(red).get("red"));
        assertEquals(List.of("red-1"), drainByGroup(all).get(""));

        registration.register(request("blue-1", "blue"), RegistrationOwner.http("blue-session"));

        Map<String, List<String>> blueAfter = drainByGroup(blue);
        assertEquals(Set.of("blue"), blueAfter.keySet());
        assertEquals(List.of("blue-1"), blueAfter.get("blue"));

        Map<String, List<String>> redAfter = drainByGroup(red);
        assertEquals(Set.of("red"), redAfter.keySet());
        assertEquals(List.of("red-1"), redAfter.get("red"));

        assertEquals(2, registry.query("orders", null, false).size());
        assertEquals(Set.of("red-1", "blue-1"), Set.copyOf(drainByGroup(all).get("")));

        blue.finishAndReleaseAll();
        red.finishAndReleaseAll();
        all.finishAndReleaseAll();
    }

    /**
     * 同一 instanceId 换组重新注册，等于这台实例在组之间迁移。
     *
     * 旧组必须也收到推送：快照只带得动新组，旧组订阅者拿不到这包推送，就会一直用着
     * 已经迁走的那台实例继续转发。
     */
    @Test
    void migratingInstanceToAnotherGroupAlsoNotifiesTheOldGroup() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        SubscriptionManager subscriptions = new SubscriptionManager();
        PushService pushService = new PushService(subscriptions, true, NameserverGeneration.processLocal());
        RegistrationService registration =
                new RegistrationService(registry, pushService, new NameserverMetricsRegistry());

        registration.register(request("i-1", "v1"), RegistrationOwner.http("v1-session"));

        // 首次注册的推送先不订阅，避免和迁组后的断言混在一起
        EmbeddedChannel v1 = new EmbeddedChannel();
        EmbeddedChannel v2 = new EmbeddedChannel();
        subscriptions.subscribe("orders", "v1", v1);
        subscriptions.subscribe("orders", "v2", v2);

        registration.register(request("i-1", "v2"), RegistrationOwner.http("v2-session"));

        Map<String, List<String>> oldGroupPushes = drainByGroup(v1);
        assertEquals(Set.of("v1"), oldGroupPushes.keySet(), "旧组必须收到推送");
        assertTrue(oldGroupPushes.get("v1").isEmpty(), "实例迁走后旧组名单里不能再留着它");

        Map<String, List<String>> newGroupPushes = drainByGroup(v2);
        assertEquals(Set.of("v2"), newGroupPushes.keySet());
        assertEquals(List.of("i-1"), newGroupPushes.get("v2"));

        v1.finishAndReleaseAll();
        v2.finishAndReleaseAll();
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

    /** 读空一条连接上的所有推送，按推送里的组名归并实例 ID；通配推送（组名 null）归到 ""。 */
    private static Map<String, List<String>> drainByGroup(EmbeddedChannel channel) {
        Map<String, List<String>> byGroup = new LinkedHashMap<>();
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            assertTrue(outbound instanceof RoverMessage, "推送必须是 RoverMessage");
            ServicePushBody body = RoverMessageCodecSupport.decodeBody(
                    (RoverMessage) outbound, ServicePushBody.class);
            String group = body.getGroup() == null ? "" : body.getGroup();
            byGroup.computeIfAbsent(group, key -> new ArrayList<>()).addAll(ids(body));
        }
        return byGroup;
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
