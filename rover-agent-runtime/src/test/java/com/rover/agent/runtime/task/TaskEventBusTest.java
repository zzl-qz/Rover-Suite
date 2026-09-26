package com.rover.agent.runtime.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.event.TaskEvent;
import com.rover.agent.core.event.TaskEventSubscriber;
import com.rover.agent.core.event.TaskEventType;
import com.rover.agent.core.event.TaskSnapshot;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.TaskStatus;
import com.rover.agent.core.model.TaskView;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * 事件总线的线程与容量语义：订阅者各自有界、互不牵连，掉队者靠快照对齐而不是回放历史。
 *
 * 这里的订阅者代表 Web 层写 SSE 的派发线程，因此「阻塞」就是「客户端读得慢」。
 */
class TaskEventBusTest {

    private static final long TIMEOUT_MILLIS = 3_000L;

    /** 快照覆盖的事件序号，模拟任务在发布前先递增序号。 */
    private final AtomicLong coveredEventId = new AtomicLong();

    private final Supplier<TaskSnapshot> snapshots = () -> new TaskSnapshot(
            new TaskView("task-1", "session-1", null, TaskStatus.RUNNING, null, "", ResourceTarget.unknown(),
                    "为什么失败？", 1L, 0L, List.of(), null, null, null),
            "已产生的解读", coveredEventId.get());

    @Test
    void slowSubscriberDropsItsOwnBacklogAndResyncsFromSnapshot() {
        TaskEventBus bus = new TaskEventBus(4);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch inHandler = new CountDownLatch(1);
        List<TaskEvent> received = new CopyOnWriteArrayList<>();
        bus.subscribe("task-1", snapshots, event -> {
            inHandler.countDown();
            awaitQuietly(release);
            received.add(event);
        });
        // 先确认订阅者卡在补发快照上，再灌事件：否则增量可能抢在派发线程之前把队列冲溢出。
        await(() -> inHandler.getCount() == 0, () -> "订阅者未收到补发快照");

        // 订阅者不动，随后的事件只能堆在它自己的队列里直至溢出。
        for (long id = 1; id <= 20; id++) {
            coveredEventId.set(id);
            bus.publish(analysisDelta(id));
        }
        release.countDown();
        // 等掉队恢复的快照投出来：它的覆盖点就是恢复那一刻的序号，之后再发布的事件才可能投递。
        await(() -> received.size() >= 2, () -> "掉队恢复快照未投递，当前收到：" + received);
        coveredEventId.set(21);
        bus.publish(new TaskEvent(21, "task-1", TaskEventType.TASK_COMPLETED, 21L, Map.of("status", "COMPLETED")));

        await(() -> received.stream().anyMatch(event -> event.type().terminal()),
                () -> "终态事件未投递，当前收到：" + received);
        // 掉队期间的事件被丢弃，改用快照对齐：第二条快照覆盖到 20，积压的增量一条都不会重复投递。
        assertEquals(List.of(TaskEventType.SNAPSHOT, TaskEventType.SNAPSHOT, TaskEventType.TASK_COMPLETED),
                received.stream().map(TaskEvent::type).toList());
        TaskSnapshot resync = (TaskSnapshot) received.get(1).payload();
        assertEquals(20L, resync.coveredEventId());
        assertEquals("已产生的解读", resync.analysis());
    }

    @Test
    void slowSubscriberDoesNotDelayOthers() {
        TaskEventBus bus = new TaskEventBus(64);
        CountDownLatch release = new CountDownLatch(1);
        List<TaskEvent> stuck = new CopyOnWriteArrayList<>();
        bus.subscribe("task-1", snapshots, event -> {
            awaitQuietly(release);
            stuck.add(event);
        });

        Recording fast = new Recording();
        bus.subscribe("task-1", snapshots, fast);
        for (long id = 1; id <= 50; id++) {
            coveredEventId.set(id);
            bus.publish(analysisDelta(id));
        }

        fast.await(event -> event.eventId() == 50L);
        try {
            // 慢订阅者一个事件都没消费完，快订阅者已经完整收到 50 条增量：两者互不牵连。
            assertEquals(51, fast.events().size());
            assertEquals(List.of(TaskEventType.SNAPSHOT),
                    fast.types().subList(0, 1));
            assertEquals(List.of(TaskEventType.ANALYSIS_DELTA),
                    fast.types().subList(1, 2));
            assertTrue(stuck.isEmpty());
        } finally {
            release.countDown();
        }
    }

    @Test
    void lateSubscriberOfFinishedTaskGetsSnapshotThenTerminal() {
        TaskEventBus bus = new TaskEventBus(8);
        Recording early = new Recording();
        bus.subscribe("task-1", snapshots, early);
        coveredEventId.set(7);
        bus.publish(new TaskEvent(7, "task-1", TaskEventType.TASK_COMPLETED, 7L, Map.of("status", "COMPLETED")));
        early.awaitTerminal();

        Recording late = new Recording();
        bus.subscribe("task-1", snapshots, late);

        late.awaitTerminal();
        assertEquals(List.of(TaskEventType.SNAPSHOT, TaskEventType.TASK_COMPLETED), late.types());
    }

    @Test
    void closedChannelStopsDelivery() {
        TaskEventBus bus = new TaskEventBus(8);
        Recording subscriber = new Recording();
        bus.subscribe("task-1", snapshots, subscriber);
        subscriber.await(event -> event.type() == TaskEventType.SNAPSHOT);

        bus.close("task-1");
        coveredEventId.set(1);
        bus.publish(analysisDelta(1));

        sleep(200L);
        assertEquals(List.of(TaskEventType.SNAPSHOT), subscriber.types());
    }

    private static TaskEvent analysisDelta(long id) {
        return new TaskEvent(id, "task-1", TaskEventType.ANALYSIS_DELTA, id, Map.of("text", "片段" + id));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(BooleanSupplier condition) {
        await(condition, () -> "等待事件超时");
    }

    private static void await(BooleanSupplier condition, Supplier<String> message) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            sleep(5L);
        }
        assertTrue(condition.getAsBoolean(), message);
    }

    /** 记录订阅到的事件；等待条件用轮询实现，避免测试依赖固定睡眠。 */
    private static final class Recording implements TaskEventSubscriber {

        private final List<TaskEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void onEvent(TaskEvent event) {
            events.add(event);
        }

        private List<TaskEvent> events() {
            return List.copyOf(events);
        }

        private List<TaskEventType> types() {
            return events.stream().map(TaskEvent::type).toList();
        }

        private void awaitTerminal() {
            await(event -> event.type().terminal());
        }

        private void await(Predicate<TaskEvent> condition) {
            long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
            while (events.stream().noneMatch(condition) && System.currentTimeMillis() < deadline) {
                sleep(5L);
            }
            assertTrue(events.stream().anyMatch(condition), "等待事件超时：" + types());
        }
    }
}