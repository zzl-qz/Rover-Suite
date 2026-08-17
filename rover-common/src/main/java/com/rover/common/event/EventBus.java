package com.rover.common.event;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-12 00:00:00
 * Description: 轻量事件总线（对齐 aero-mq：SPI/register + 异步精确分发）
 */
@Slf4j
public class EventBus {

    private final Map<Class<? extends Event>, List<EventListener<?>>> listenerMap = new ConcurrentHashMap<>();
    /** 有序事件：同一 orderKey 串行，避免同连接上注册/断线对打 */
    private final ConcurrentHashMap<Object, SerialLane> serialLanes = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor;
    private final long shutdownTimeoutSeconds;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong publishCount = new AtomicLong(0);
    private final AtomicLong dispatchFailCount = new AtomicLong(0);

    public EventBus(String name) {
        this(name, new Config());
    }

    public EventBus(String name, Config config) {
        this.shutdownTimeoutSeconds = config.shutdownTimeoutSeconds;
        this.executor = new ThreadPoolExecutor(
                config.corePoolSize,
                config.maxPoolSize,
                config.keepAliveSeconds,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(config.queueCapacity),
                threadFactory(name),
                config.rejectedExecutionHandler);
    }

    /** SPI 加载无依赖监听器；业务 Listener 更推荐显式 register */
    public void init() {
        if (closed.get()) {
            throw new IllegalStateException("事件总线已关闭，无法初始化");
        }
        if (!initialized.compareAndSet(false, true)) {
            log.warn("事件总线已初始化，忽略重复调用");
            return;
        }
        ServiceLoader<EventListener> loader = ServiceLoader.load(EventListener.class);
        for (EventListener<?> listener : loader) {
            try {
                register(listener);
                log.info("SPI 加载事件监听器: {}", listener.getClass().getName());
            } catch (Exception ex) {
                log.error("加载事件监听器失败，已跳过: {}", listener.getClass().getName(), ex);
            }
        }
    }

    /** 编程注册（Nameserver 业务 Listener / 单测用这个） */
    public synchronized void register(EventListener<?> listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener 不能为 null");
        }
        if (closed.get()) {
            throw new IllegalStateException("事件总线已关闭，无法注册监听器");
        }
        Class<? extends Event> eventType = resolveEventType(listener);
        listenerMap.computeIfAbsent(eventType, key -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /**
     * 异步发布：按事件具体类精确匹配。
     * 无监听器 / 已关闭 → 直接返回；单个监听器失败不影响其它。
     * 若事件实现 {@link OrderedEvent} 且 orderKey 非空，则同一 key 按发布顺序串行。
     */
    public void publish(Event event) {
        if (event == null || closed.get()) {
            return;
        }
        List<EventListener<?>> listeners = listenerMap.get(event.getClass());
        if (listeners == null || listeners.isEmpty()) {
            return;
        }
        publishCount.incrementAndGet();
        Object orderKey = resolveOrderKey(event);
        Runnable task = () -> dispatch(event, listeners);
        try {
            if (orderKey == null) {
                executor.execute(task);
            } else {
                executeSerial(orderKey, task);
            }
        } catch (RejectedExecutionException ex) {
            log.warn("异步事件提交被拒绝: event={}", event.getClass().getSimpleName(), ex);
        }
    }

    private static Object resolveOrderKey(Event event) {
        if (event instanceof OrderedEvent ordered) {
            return ordered.orderKey();
        }
        return null;
    }

    /** 同一 key 排队，串到线程池里顺序执行，执行完若队列空则摘掉 lane，避免泄漏。 */
    private void executeSerial(Object orderKey, Runnable task) {
        SerialLane lane = serialLanes.computeIfAbsent(orderKey, key -> new SerialLane());
        lane.queue.add(task);
        drainSerial(orderKey, lane);
    }

    private void drainSerial(Object orderKey, SerialLane lane) {
        if (!lane.running.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    Runnable next;
                    while ((next = lane.queue.poll()) != null) {
                        next.run();
                    }
                } finally {
                    lane.running.set(false);
                    if (!lane.queue.isEmpty()) {
                        drainSerial(orderKey, lane);
                    } else {
                        // 队列空且不再跑：尝试摘掉，减少已关闭连接的 key 残留
                        serialLanes.remove(orderKey, lane);
                        if (!lane.queue.isEmpty()) {
                            SerialLane revived = serialLanes.computeIfAbsent(orderKey, key -> lane);
                            drainSerial(orderKey, revived);
                        }
                    }
                }
            });
        } catch (RuntimeException ex) {
            lane.running.set(false);
            throw ex;
        }
    }

    @SuppressWarnings("unchecked")
    private void dispatch(Event event, List<EventListener<?>> listeners) {
        for (EventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception ex) {
                dispatchFailCount.incrementAndGet();
                log.error("事件监听器执行失败: event={}, listener={}",
                        event.getClass().getSimpleName(), listener.getClass().getName(), ex);
            }
        }
    }

    /** 单 key 串行车道：队列 + 是否已有 worker 在排空。 */
    private static final class SerialLane {
        private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean running = new AtomicBoolean(false);
    }

    public void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    public long getPublishCount() {
        return publishCount.get();
    }

    public long getDispatchFailCount() {
        return dispatchFailCount.get();
    }

    private static ThreadFactory threadFactory(String name) {
        AtomicInteger seq = new AtomicInteger(1);
        return r -> {
            Thread thread = new Thread(r, "rover-event-bus-" + name + "-" + seq.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** 解析 Listener 直接或经泛型基类实现的 EventListener&lt;T&gt; 上的 T。 */
    @SuppressWarnings("unchecked")
    static Class<? extends Event> resolveEventType(EventListener<?> listener) {
        Type resolved = resolveEventType(listener.getClass(), new HashMap<>());
        if (resolved instanceof Class<?> clazz && Event.class.isAssignableFrom(clazz)) {
            return (Class<? extends Event>) clazz;
        }
        throw new IllegalArgumentException("无法解析监听器事件类型: " + listener.getClass().getName());
    }

    private static Type resolveEventType(Type type, Map<TypeVariable<?>, Type> bindings) {
        if (type instanceof ParameterizedType parameterized) {
            if (!(parameterized.getRawType() instanceof Class<?> rawClass)) {
                return null;
            }
            Type[] actual = parameterized.getActualTypeArguments();
            TypeVariable<?>[] variables = rawClass.getTypeParameters();
            Map<TypeVariable<?>, Type> nested = new HashMap<>(bindings);
            for (int i = 0; i < variables.length; i++) {
                nested.put(variables[i], resolveBinding(actual[i], bindings));
            }
            if (rawClass == EventListener.class) {
                return resolveBinding(actual[0], bindings);
            }
            return resolveFromClass(rawClass, nested);
        }
        if (type instanceof Class<?> clazz) {
            return resolveFromClass(clazz, bindings);
        }
        return null;
    }

    private static Type resolveFromClass(Class<?> clazz, Map<TypeVariable<?>, Type> bindings) {
        for (Type genericInterface : clazz.getGenericInterfaces()) {
            Type resolved = resolveEventType(genericInterface, bindings);
            if (resolved != null) {
                return resolved;
            }
        }
        Type superclass = clazz.getGenericSuperclass();
        if (superclass != null && superclass != Object.class) {
            return resolveEventType(superclass, bindings);
        }
        return null;
    }

    private static Type resolveBinding(Type type, Map<TypeVariable<?>, Type> bindings) {
        Type current = type;
        while (current instanceof TypeVariable<?> variable && bindings.containsKey(variable)) {
            Type next = bindings.get(variable);
            if (next == current) {
                break;
            }
            current = next;
        }
        return current;
    }

    public static class Config {
        private int corePoolSize = 2;
        private int maxPoolSize = 10;
        private int queueCapacity = 1024;
        private long keepAliveSeconds = 60;
        private long shutdownTimeoutSeconds = 5;
        // 默认 CallerRunsPolicy：线程池/队列撑不住时回退到发布线程执行，不丢事件。
        // 正常路径仍走异步池；只有背压打满才会偶发占到 Netty/业务发布线程。
        private RejectedExecutionHandler rejectedExecutionHandler = new ThreadPoolExecutor.CallerRunsPolicy();

        public Config corePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
            return this;
        }

        public Config maxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
            return this;
        }

        public Config queueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
            return this;
        }

        public Config keepAliveSeconds(long keepAliveSeconds) {
            this.keepAliveSeconds = keepAliveSeconds;
            return this;
        }

        public Config shutdownTimeoutSeconds(long shutdownTimeoutSeconds) {
            this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
            return this;
        }

        public Config rejectedExecutionHandler(RejectedExecutionHandler rejectedExecutionHandler) {
            this.rejectedExecutionHandler = rejectedExecutionHandler;
            return this;
        }
    }
}
