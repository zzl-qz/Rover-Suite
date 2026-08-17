package com.rover.common.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EventBusTest {

    @Test
    void resolvesEventTypeThroughGenericBaseClass() throws Exception {
        EventBus bus = new EventBus("generic-test");
        CountDownLatch handled = new CountDownLatch(1);
        bus.register(new ConcreteListener(handled));

        bus.publish(new TestEvent(null, 1));

        org.junit.jupiter.api.Assertions.assertTrue(handled.await(2, TimeUnit.SECONDS));
        bus.shutdown();
    }

    @Test
    void preservesOrderForSameKey() throws Exception {
        EventBus bus = new EventBus("ordered-test");
        CountDownLatch handled = new CountDownLatch(3);
        List<Integer> values = new CopyOnWriteArrayList<>();
        bus.register(new EventListener<TestEvent>() {
            @Override
            public void onEvent(TestEvent event) {
                values.add(event.value);
                handled.countDown();
            }
        });

        Object key = new Object();
        bus.publish(new TestEvent(key, 1));
        bus.publish(new TestEvent(key, 2));
        bus.publish(new TestEvent(key, 3));

        org.junit.jupiter.api.Assertions.assertTrue(handled.await(2, TimeUnit.SECONDS));
        assertEquals(List.of(1, 2, 3), values);
        bus.shutdown();
    }

    private abstract static class BaseListener<T extends Event> implements EventListener<T> {
    }

    private static final class ConcreteListener extends BaseListener<TestEvent> {
        private final CountDownLatch handled;

        private ConcreteListener(CountDownLatch handled) {
            this.handled = handled;
        }

        @Override
        public void onEvent(TestEvent event) {
            handled.countDown();
        }
    }

    private static final class TestEvent extends Event implements OrderedEvent {
        private final Object key;
        private final int value;

        private TestEvent(Object key, int value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public Object orderKey() {
            return key;
        }
    }
}
