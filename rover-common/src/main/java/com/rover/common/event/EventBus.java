package com.rover.common.event;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
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
        try {
            executor.execute(() -> dispatch(event, listeners));
        } catch (RejectedExecutionException ex) {
            log.warn("异步事件提交被拒绝: event={}", event.getClass().getSimpleName(), ex);
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

    /** 解析 Listener 直接实现的 EventListener&lt;T&gt; 上的 T */
    @SuppressWarnings("unchecked")
    static Class<? extends Event> resolveEventType(EventListener<?> listener) {
        for (Type type : listener.getClass().getGenericInterfaces()) {
            if (type instanceof ParameterizedType parameterized
                    && parameterized.getRawType() == EventListener.class) {
                Type arg = parameterized.getActualTypeArguments()[0];
                if (arg instanceof Class<?> clazz && Event.class.isAssignableFrom(clazz)) {
                    return (Class<? extends Event>) clazz;
                }
            }
        }
        throw new IllegalArgumentException("无法解析监听器事件类型: " + listener.getClass().getName());
    }

    public static class Config {
        private int corePoolSize = 2;
        private int maxPoolSize = 10;
        private int queueCapacity = 1024;
        private long keepAliveSeconds = 60;
        private long shutdownTimeoutSeconds = 5;
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
