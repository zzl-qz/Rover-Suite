package com.rover.agent.runtime.task;

import com.rover.agent.core.event.*;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.repository.AgentTaskRepository;
import java.util.concurrent.atomic.AtomicBoolean;

/** 持久 outbox 的按游标消费；连接断开不改变业务状态，重连可以至少一次重放。 */
public final class DurableTaskEvents {
    private DurableTaskEvents() { }
    public static TaskEventSubscription subscribe(AgentTaskRepository records, TaskView task, long after,
                                                  TaskEventSubscriber subscriber) {
        AtomicBoolean open = new AtomicBoolean(true);
        Thread worker = new Thread(() -> {
            long cursor = Math.max(0, after);
            try {
                var snapshot = records.streamSnapshot(task.taskId()).orElse(new TaskSnapshot(task, "", 0, ""));
                if (after < 0 || after > snapshot.coveredEventId()) {
                    subscriber.onEvent(new TaskEvent(snapshot.coveredEventId(), task.taskId(), TaskEventType.SNAPSHOT, System.currentTimeMillis(), snapshot));
                    cursor = snapshot.coveredEventId();
                }
                while (open.get()) {
                    var batch = records.eventsAfter(task.taskId(), cursor, 128);
                    for (TaskEvent event : batch) {
                        if (!open.get()) { return; }
                        subscriber.onEvent(event);
                        cursor = event.eventId();
                        if (event.type().terminal() || event.type() == TaskEventType.CLARIFICATION_REQUIRED) { return; }
                    }
                    if (batch.isEmpty()) {
                        var latest = records.find(task.taskId()).orElse(null);
                        if (latest == null) { return; }
                        if (!latest.status().active()) {
                            subscriber.onEvent(new TaskEvent(cursor, task.taskId(), TaskEventType.SNAPSHOT, System.currentTimeMillis(),
                                    new TaskSnapshot(latest, latest.result() == null ? "" : latest.result().aiAnalysis(), cursor, "")));
                            TaskEventType type = latest.status() == com.rover.agent.core.model.TaskStatus.COMPLETED ? TaskEventType.TASK_COMPLETED
                                    : latest.status() == com.rover.agent.core.model.TaskStatus.WAITING_INPUT ? TaskEventType.CLARIFICATION_REQUIRED
                                    : latest.status() == com.rover.agent.core.model.TaskStatus.CANCELLED ? TaskEventType.TASK_CANCELLED : TaskEventType.TASK_FAILED;
                            subscriber.onEvent(new TaskEvent(cursor, task.taskId(), type, System.currentTimeMillis(), latest));
                            return;
                        }
                        Thread.sleep(200);
                    }
                }
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            finally { open.set(false); }
        }, "rover-durable-events-" + task.taskId());
        worker.setDaemon(true);
        worker.start();
        return () -> { open.set(false); worker.interrupt(); };
    }
}
