package com.rover.agent.runtime.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.event.TaskEvent;
import com.rover.agent.core.event.TaskEventType;
import com.rover.agent.core.model.TaskStatus;
import com.rover.agent.runtime.metrics.AgentMetrics;
import com.rover.agent.runtime.repository.InMemoryAgentTaskRepository;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 协作式取消的语义：仍执行中的任务可被取消（置 CANCELLED 并发布 {@code TASK_CANCELLED}），
 * 已终态的任务再次取消无效。取消只改状态与事件，不动快照里的其它字段。
 */
class InvestigationTaskRegistryCancelTest {

    private InvestigationTaskRegistry newRegistry() {
        return new InvestigationTaskRegistry(new InMemoryAgentTaskRepository(50),
                AgentExecutionSettings.defaults(), new TaskEventBus(), AgentMetrics.NOOP);
    }

    @Test
    void cancelMarksTaskCancelledAndPublishesEvent() throws InterruptedException {
        InvestigationTaskRegistry registry = newRegistry();
        String taskId = registry.register("s1", "为什么失败？").taskId();

        CountDownLatch fired = new CountDownLatch(1);
        AtomicReference<TaskEventType> last = new AtomicReference<>();
        registry.find(taskId).subscribe(event -> {
            last.set(event.type());
            fired.countDown();
        });

        assertTrue(registry.cancel(taskId));
        assertEquals(TaskStatus.CANCELLED, registry.get(taskId).status());
        assertTrue(fired.await(2, TimeUnit.SECONDS), "应发布 TASK_CANCELLED 事件");
        assertEquals(TaskEventType.TASK_CANCELLED, last.get());

        // 已终态的任务再次取消无效。
        assertFalse(registry.cancel(taskId));
    }

    @Test
    void cancelUnknownTaskReturnsFalse() {
        InvestigationTaskRegistry registry = newRegistry();
        assertFalse(registry.cancel("nope"));
    }

    @Test
    void queueGaugeReturnsToZeroWhenWorkerDequeuesTask() throws InterruptedException {
        var queueDepths = new CopyOnWriteArrayList<Integer>();
        AgentMetrics metrics = new AgentMetrics() {
            @Override public void taskQueued(int queueSize) { queueDepths.add(queueSize); }
        };
        var registry = new InvestigationTaskRegistry(new InMemoryAgentTaskRepository(10),
                new AgentExecutionSettings(1, 1, 10), new TaskEventBus(), metrics);
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        try {
            registry.execute(() -> {
                firstStarted.countDown();
                try { releaseFirst.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            registry.execute(secondStarted::countDown);
            assertTrue(queueDepths.contains(1), "second task should appear in the waiting queue");

            releaseFirst.countDown();
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
            assertEquals(0, queueDepths.get(queueDepths.size() - 1),
                    "dequeue must clear the queue-depth gauge before the second task runs");
        } finally {
            releaseFirst.countDown();
            registry.stop();
        }
    }
}
