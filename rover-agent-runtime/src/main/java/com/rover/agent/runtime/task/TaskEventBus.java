package com.rover.agent.runtime.task;

import com.rover.agent.core.event.TaskEvent;
import com.rover.agent.core.event.TaskEventSubscriber;
import com.rover.agent.core.event.TaskEventSubscription;
import com.rover.agent.core.event.TaskEventType;
import com.rover.agent.core.event.TaskSnapshot;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 任务事件总线，发布时仅写入每个订阅者的有界队列，由独立线程派发。
 * 队列满时丢弃该订阅者的积压，恢复投递前补发全量快照并跳过已覆盖事件。
 * 任务回收时关闭通道。
 */
public final class TaskEventBus {

    /** 单个订阅者的积压上限：约等于「一个页面上千条事件」的观察窗口，超过即判定为慢订阅者。 */
    static final int DEFAULT_SUBSCRIBER_QUEUE_CAPACITY = 256;

    private static final long WAKEUP_MILLIS = 200L;

    private final int queueCapacity;
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();

    public TaskEventBus() {
        this(DEFAULT_SUBSCRIBER_QUEUE_CAPACITY);
    }

    public TaskEventBus(int queueCapacity) {
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("订阅队列容量必须为正数");
        }
        this.queueCapacity = queueCapacity;
    }

    /**
     * 发布一个事件：只在任务状态锁内做非阻塞入队，不做任何网络 IO。
     *
     * 慢订阅者或写阻塞只能丢弃它自己的积压，绝不能拖慢模型调用与 Agent Worker。
     */
    public void publish(TaskEvent event) {
        Channel channel = channels.get(event.taskId());
        if (channel != null) {
            channel.publish(event);
        }
    }

    /**
     * 任务登记时开通道：即使全程无人订阅，终态事件也留在这里，晚连上的订阅者才能照常收尾。
     *
     * 通道数量随任务容量有界，由 {@link #close(String)} 回收。
     */
    public void open(String taskId) {
        channels.computeIfAbsent(taskId, id -> new Channel(id, queueCapacity));
    }

    /**
     * 订阅任务，先发送快照再投递增量；调用方须持有任务状态锁。
     *
     * @param taskId         被观察的任务
     * @param snapshotSource 读取任务当前快照（任务锁内的只读方法），用于补发与掉队重同步
     * @param subscriber     事件消费者，在独立派发线程上被调用
     */
    public TaskEventSubscription subscribe(String taskId, Supplier<TaskSnapshot> snapshotSource,
                                           TaskEventSubscriber subscriber) {
        return channels.computeIfAbsent(taskId, id -> new Channel(id, queueCapacity))
                .subscribe(snapshotSource, subscriber);
    }

    /** 关闭某任务的观察通道：任务被淘汰或回滚时调用，之后发布的事件不再有人接收。 */
    public void close(String taskId) {
        Channel channel = channels.remove(taskId);
        if (channel != null) {
            channel.close();
        }
    }

    /** 任务事件的投递通道：按任务隔离，一个任务的事件不会串到另一个任务。 */
    private static final class Channel {

        private final String taskId;
        private final int queueCapacity;
        private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

        /** 最后一个终态事件：晚订阅时在快照之后补发，订阅者才能知道任务已经结束。 */
        private volatile TaskEvent terminal;

        private Channel(String taskId, int queueCapacity) {
            this.taskId = taskId;
            this.queueCapacity = queueCapacity;
        }

        private void publish(TaskEvent event) {
            if (event.type().terminal()) {
                terminal = event;
            }
            for (Subscriber subscriber : subscribers) {
                subscriber.offer(event);
            }
        }

        private TaskEventSubscription subscribe(Supplier<TaskSnapshot> snapshotSource,
                                                TaskEventSubscriber observer) {
            Subscriber subscriber = new Subscriber(this, snapshotSource, observer);
            subscriber.offer(snapshotEvent(snapshotSource.get()));
            TaskEvent finished = terminal;
            if (finished != null) {
                subscriber.offer(finished);
            }
            subscribers.add(subscriber);
            subscriber.start();
            return subscriber;
        }

        private void close() {
            for (Subscriber subscriber : subscribers) {
                subscriber.cancel();
            }
        }

        private void remove(Subscriber subscriber) {
            subscribers.remove(subscriber);
        }
    }

    /** 一个订阅者的投递状态：有界队列 + 独立派发线程 + 掉队标记。 */
    private static final class Subscriber implements TaskEventSubscription {

        private final Channel channel;
        private final Supplier<TaskSnapshot> snapshotSource;
        private final TaskEventSubscriber observer;
        private final BlockingQueue<TaskEvent> queue;
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final AtomicBoolean lagged = new AtomicBoolean();

        /** 派发线程私有：快照已覆盖到的事件序号；初值 -1 保证首条快照事件不被它自己跳过。 */
        private long coveredEventId = -1L;

        private Thread thread;

        private Subscriber(Channel channel, Supplier<TaskSnapshot> snapshotSource,
                           TaskEventSubscriber observer) {
            this.channel = channel;
            this.snapshotSource = snapshotSource;
            this.observer = observer;
            this.queue = new ArrayBlockingQueue<>(channel.queueCapacity);
        }

        private void offer(TaskEvent event) {
            if (!open.get()) {
                return;
            }
            if (!queue.offer(event)) {
                // 慢订阅者：丢掉自己的积压并标记掉队，只保留最新事件（终态事件因此不会被挤掉）。
                lagged.set(true);
                queue.clear();
                queue.offer(event);
            }
        }

        private void start() {
            thread = new Thread(this::dispatch, "rover-task-events-" + channel.taskId);
            thread.setDaemon(true);
            thread.start();
        }

        private void dispatch() {
            try {
                while (open.get()) {
                    TaskEvent event = queue.poll(WAKEUP_MILLIS, TimeUnit.MILLISECONDS);
                    if (event == null) {
                        continue;
                    }
                    if (lagged.compareAndSet(true, false)) {
                        TaskSnapshot snapshot = snapshotSource.get();
                        coveredEventId = snapshot.coveredEventId();
                        observer.onEvent(snapshotEvent(snapshot));
                    }
                    if (event.eventId() <= coveredEventId && !event.type().terminal()) {
                        // 终态事件不被快照吞掉：它是订阅者的收尾信号，掉了这段流就永远结束不了。
                        continue;
                    }
                    observer.onEvent(event);
                    if (event.type().terminal()) {
                        break;
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                cancel();
            }
        }

        @Override
        public void cancel() {
            if (open.compareAndSet(true, false)) {
                queue.clear();
                channel.remove(this);
            }
        }
    }

    /** 快照事件的 eventId 就是它覆盖到的事件序号：订阅者据此跳过重复事件。 */
    private static TaskEvent snapshotEvent(TaskSnapshot snapshot) {
        return new TaskEvent(snapshot.coveredEventId(), snapshot.task().taskId(), TaskEventType.SNAPSHOT,
                System.currentTimeMillis(), snapshot);
    }
}