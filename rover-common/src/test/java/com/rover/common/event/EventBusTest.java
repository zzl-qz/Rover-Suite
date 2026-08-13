package com.rover.common.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 瘦身后 EventBus：精确匹配、异步、关闭忽略、单监听器失败隔离 */
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
        assertEquals(DemoEvent.class, EventBus.resolveEventType(new DirectListener()));
    }

    @Test
    void publish_async_exactMatch() throws Exception {
        bus = new EventBus("test-async");
        CountDownLatch latch = new CountDownLatch(1);
        List<String> names = new ArrayList<>();
        bus.register(new EventListener<DemoEvent>() {
            @Override
            public void onEvent(DemoEvent event) {
                names.add(event.getName());
                latch.countDown();
            }
        });
        DemoEvent event = new DemoEvent();
        event.setName("order");
        bus.publish(event);
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("order"), names);
        assertEquals(1, bus.getPublishCount());
    }

    @Test
    void publish_listenerFailure_doesNotStopOthers() throws Exception {
        bus = new EventBus("test-iso");
        CountDownLatch latch = new CountDownLatch(1);
        bus.register(new EventListener<DemoEvent>() {
            @Override
            public void onEvent(DemoEvent event) {
                throw new IllegalStateException("boom");
            }
        });
        bus.register(new EventListener<DemoEvent>() {
            @Override
            public void onEvent(DemoEvent event) {
                latch.countDown();
            }
        });
        bus.publish(new DemoEvent());
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, bus.getDispatchFailCount());
    }

    @Test
    void publish_afterShutdown_isIgnored() throws Exception {
        bus = new EventBus("test-close");
        AtomicInteger hits = new AtomicInteger();
        bus.register(new EventListener<DemoEvent>() {
            @Override
            public void onEvent(DemoEvent event) {
                hits.incrementAndGet();
            }
        });
        bus.shutdown();
        bus.publish(new DemoEvent());
        Thread.sleep(200);
        assertEquals(0, hits.get());
        assertTrue(bus.isClosed());
    }

    @Test
    void publish_noListener_noNpe() {
        bus = new EventBus("test-empty");
        bus.publish(new DemoEvent());
        assertEquals(0, bus.getPublishCount());
    }

    static class DemoEvent extends Event {
        private String name;

        String getName() {
            return name;
        }

        void setName(String name) {
            this.name = name;
        }
    }

    static class DirectListener implements EventListener<DemoEvent> {
        @Override
        public void onEvent(DemoEvent event) {
        }
    }
}
