package com.rover.common.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * EventBus 行为单测：解析、同步 fail-fast、异步、关闭降级、父类匹配。
 */
class EventBusTest {

    private EventBus bus;

    @AfterEach
    void tearDown() {
        if (bus != null) {
            bus.shutdown();
        }
    }

    @Test
    void resolve_directImplements() {
        assertEquals(ServiceChangeEvent.class, EventBus.resolveEventType(new DirectListener()));
    }

    @Test
    void resolve_viaIntermediateInterface() {
        assertEquals(ServiceChangeEvent.class, EventBus.resolveEventType(new ViaInterfaceListener()));
    }

    @Test
    void resolve_viaGenericBaseClass() {
        assertEquals(ServiceChangeEvent.class, EventBus.resolveEventType(new ViaBaseListener()));
    }

    @Test
    void publishSync_failFast_stopsLaterListeners() {
        bus = new EventBus("test-sync");
        AtomicInteger second = new AtomicInteger();
        bus.register(new EventListener<ServiceChangeEvent>() {
            @Override
            public void onEvent(ServiceChangeEvent event) {
                throw new IllegalStateException("boom");
            }
        });
        bus.register(new EventListener<ServiceChangeEvent>() {
            @Override
            public void onEvent(ServiceChangeEvent event) {
                second.incrementAndGet();
            }
        });

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> bus.publishSync(ServiceChangeEvent.of("s", null, 1, ServiceChangeType.REGISTER, List.of())));
        assertTrue(ex.getCause() instanceof IllegalStateException);
        assertEquals(0, second.get());
        assertEquals(1, bus.getDispatchFailCount());
    }

    @Test
    void publish_async_delivers() throws Exception {
        bus = new EventBus("test-async");
        CountDownLatch latch = new CountDownLatch(1);
        List<String> names = new ArrayList<>();
        bus.register(new EventListener<ServiceChangeEvent>() {
            @Override
            public void onEvent(ServiceChangeEvent event) {
                names.add(event.getServiceName());
                latch.countDown();
            }
        });
        bus.publish(ServiceChangeEvent.of("order", "g1", 2, ServiceChangeType.REGISTER, List.of()));
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("order"), names);
        assertEquals(1, bus.getPublishCount());
    }

    @Test
    void publish_afterShutdown_isIgnored() {
        bus = new EventBus("test-close");
        AtomicInteger hits = new AtomicInteger();
        bus.register(new EventListener<ServiceChangeEvent>() {
            @Override
            public void onEvent(ServiceChangeEvent event) {
                hits.incrementAndGet();
            }
        });
        bus.shutdown();
        bus.publish(ServiceChangeEvent.of("s", null, 1, ServiceChangeType.REGISTER, List.of()));
        bus.publishSync(ServiceChangeEvent.of("s", null, 1, ServiceChangeType.REGISTER, List.of()));
        assertEquals(0, hits.get());
        assertTrue(bus.getIgnoredAfterCloseCount() >= 2);
        assertTrue(bus.isClosed());
    }

    @Test
    void match_parentListener_receivesChildEvent() throws Exception {
        bus = new EventBus("test-parent");
        CountDownLatch latch = new CountDownLatch(1);
        bus.register(new EventListener<BaseDemoEvent>() {
            @Override
            public void onEvent(BaseDemoEvent event) {
                latch.countDown();
            }
        });
        bus.publish(new ChildDemoEvent());
        assertTrue(latch.await(2, TimeUnit.SECONDS));
    }

    // --- 测试用监听器 / 事件 ---

    static class DirectListener implements EventListener<ServiceChangeEvent> {
        @Override
        public void onEvent(ServiceChangeEvent event) {
        }
    }

    interface ServiceChangeListener extends EventListener<ServiceChangeEvent> {
    }

    static class ViaInterfaceListener implements ServiceChangeListener {
        @Override
        public void onEvent(ServiceChangeEvent event) {
        }
    }

    abstract static class BaseListener<E extends Event> implements EventListener<E> {
    }

    static class ViaBaseListener extends BaseListener<ServiceChangeEvent> {
        @Override
        public void onEvent(ServiceChangeEvent event) {
        }
    }

    static class BaseDemoEvent implements Event {
    }

    static class ChildDemoEvent extends BaseDemoEvent {
    }
}
