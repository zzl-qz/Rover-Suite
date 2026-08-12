package com.rover.common.event;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-12 00:00:00
 * Description: 事件总线
 *
 * 职责：事件类型 -> 监听器列表的映射，并提供同步/异步两种派发语义。
 * 加载：init() 通过 SPI 加载 EventListener 实现，反射解析其监听的事件类型。
 * 语义：
 *   - publish：异步派发，用于可乱序、可丢失的副作用（审计、指标、缓存刷新）。
 *   - publishSync：调用线程顺序派发，监听器失败会向上抛出，用于顺序敏感的主链路（注册/注销/心跳）。
 */
@Slf4j
public class EventBus {

    /** 事件类型 -> 监听器列表 */
    private final Map<Class<? extends Event>, List<EventListener>> listenerMap = new ConcurrentHashMap<>();

    /** 异步派发线程池 */
    private final ThreadPoolExecutor executor;

    /** 关闭超时（秒） */
    private final long shutdownTimeoutSeconds;

    /** 是否已初始化，防止重复加载 SPI 导致监听器重复注册 */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /** 已发布事件数（异步 + 同步），用于排障观测 */
    private final AtomicLong publishCount = new AtomicLong(0);

    /** 监听器执行失败次数，用于排障观测 */
    private final AtomicLong dispatchFailCount = new AtomicLong(0);

    /** 使用默认配置构造事件总线。 */
    public EventBus(String name) {
        this(name, new Config());
    }

    /** 使用自定义配置构造事件总线。 */
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

    /**
     * 初始化：通过 SPI 加载所有 EventListener 实现，
     * 反射解析每个监听器监听的事件类型，建立事件类型 -> 监听器映射。
     *
     * 幂等：重复调用不会重复注册监听器。
     * 容错：单个监听器解析失败仅记录告警并跳过，不拖垮整体启动。
     */
    public void init() {
        if (!initialized.compareAndSet(false, true)) {
            log.warn("事件总线已初始化，忽略重复调用");
            return;
        }
        ServiceLoader<EventListener> loader = ServiceLoader.load(EventListener.class);
        for (EventListener listener : loader) {
            try {
                Class<? extends Event> eventType = resolveEventType(listener);
                listenerMap.computeIfAbsent(eventType, key -> new CopyOnWriteArrayList<>()).add(listener);
                log.info("加载事件监听器: {} -> {}", eventType.getSimpleName(), listener.getClass().getName());
            } catch (Exception ex) {
                log.error("加载事件监听器失败，已跳过: {}", listener.getClass().getName(), ex);
            }
        }
    }

    /**
     * 异步发布：按事件具体类型匹配监听器，提交到线程池执行，不阻塞发布线程。
     * 适用于可乱序、可丢失的副作用。队列满时采用配置的拒绝策略（默认调用者执行，形成背压）。
     */
    public void publish(Event event) {
        List<EventListener> listeners = match(event);
        if (listeners.isEmpty()) {
            return;
        }
        publishCount.incrementAndGet();
        executor.execute(() -> dispatchSafely(event, listeners));
    }

    /**
     * 同步发布：在调用线程内顺序派发，保证事件顺序与执行完成。
     * 任一监听器抛异常，会在全部监听器执行完后向上抛出，调用方可感知主链路失败。
     */
    public void publishSync(Event event) {
        List<EventListener> listeners = match(event);
        if (listeners.isEmpty()) {
            return;
        }
        publishCount.incrementAndGet();
        dispatchStrictly(event, listeners);
    }

    /** 按事件具体类型精确匹配监听器；事件为 null 或无匹配时返回空列表 */
    private List<EventListener> match(Event event) {
        if (event == null) {
            return List.of();
        }
        List<EventListener> listeners = listenerMap.get(event.getClass());
        return listeners == null ? List.of() : listeners;
    }

    /** 异步派发：单个监听器异常仅记日志，不影响其余监听器，也不影响调用方。 */
    private void dispatchSafely(Event event, List<EventListener> listeners) {
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

    /** 同步派发：逐一遍历监听器，收集首个异常并在全部执行完后向上抛出。 */
    private void dispatchStrictly(Event event, List<EventListener> listeners) {
        Exception first = null;
        for (EventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception ex) {
                dispatchFailCount.incrementAndGet();
                if (first == null) {
                    first = ex;
                }
                log.error("事件监听器执行失败: event={}, listener={}",
                        event.getClass().getSimpleName(), listener.getClass().getName(), ex);
            }
        }
        if (first != null) {
            throw new RuntimeException("事件派发失败: event=" + event.getClass().getSimpleName(), first);
        }
    }

    /** 关闭总线：停止接收新任务，等待队列中的任务在超时时间内执行完，超时则强制中断。 */
    public void shutdown() {
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

    /** 待派发任务数（异步队列积压量），用于背压与排障观测。 */
    public int getPendingTaskCount() {
        return executor.getQueue().size();
    }

    /** 已发布事件总数。 */
    public long getPublishCount() {
        return publishCount.get();
    }

    /** 监听器执行失败总数。 */
    public long getDispatchFailCount() {
        return dispatchFailCount.get();
    }

    private static ThreadFactory threadFactory(String name) {
        return r -> {
            Thread thread = new Thread(r, "rover-event-bus-" + name + "-" + UUID.randomUUID());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * 反射解析监听器监听的事件类型。
     *
     * 支持两种常见声明方式：
     *   1. 直接实现：class X implements EventListener&lt;FooEvent&gt;
     *   2. 经泛型父类/接口间接实现：class X extends BaseListener&lt;FooEvent&gt;，
     *      其中 BaseListener&lt;T extends Event&gt; implements EventListener&lt;T&gt;
     */
    @SuppressWarnings("unchecked")
    private static Class<? extends Event> resolveEventType(EventListener listener) {
        Class<?> eventType = resolveEventType(listener.getClass(), Collections.emptyMap());
        if (eventType == null) {
            throw new IllegalArgumentException("无法解析监听器的事件类型: " + listener.getClass().getName());
        }
        return (Class<? extends Event>) eventType;
    }

    private static Class<?> resolveEventType(Class<?> clazz, Map<TypeVariable<?>, Type> typeVariables) {
        // 1. 直接实现的泛型接口（通常是 EventListener<XxxEvent>）
        for (Type genericInterface : clazz.getGenericInterfaces()) {
            if (genericInterface instanceof ParameterizedType parameterizedType
                    && parameterizedType.getRawType() == EventListener.class) {
                Class<?> resolved = toClass(parameterizedType.getActualTypeArguments()[0], typeVariables);
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        // 2. 沿父类链向上，维护类型变量 -> 实际类型的映射
        Type genericSuperclass = clazz.getGenericSuperclass();
        if (genericSuperclass == null) {
            return null;
        }
        Map<TypeVariable<?>, Type> next = typeVariables;
        Class<?> superClass;
        if (genericSuperclass instanceof ParameterizedType parameterizedType) {
            superClass = (Class<?>) parameterizedType.getRawType();
            next = new HashMap<>(typeVariables);
            TypeVariable<?>[] vars = superClass.getTypeParameters();
            Type[] args = parameterizedType.getActualTypeArguments();
            for (int i = 0; i < vars.length; i++) {
                next.put(vars[i], args[i]);
            }
        } else if (genericSuperclass instanceof Class<?> c) {
            superClass = c;
        } else {
            return null;
        }
        return resolveEventType(superClass, next);
    }

    private static Class<?> toClass(Type type, Map<TypeVariable<?>, Type> typeVariables) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof TypeVariable<?> typeVariable) {
            Type resolved = typeVariables.get(typeVariable);
            if (resolved != null && resolved != type) {
                return toClass(resolved, typeVariables);
            }
            return null;
        }
        if (type instanceof ParameterizedType parameterizedType) {
            return toClass(parameterizedType.getRawType(), typeVariables);
        }
        return null;
    }

    /**
     * 事件总线配置。
     */
    public static class Config {

        /** 核心线程数 */
        private int corePoolSize = 2;
        /** 最大线程数 */
        private int maxPoolSize = 10;
        /** 任务队列容量 */
        private int queueCapacity = 1024;
        /** 空闲线程存活时间（秒） */
        private long keepAliveSeconds = 60;
        /** 关闭时等待队列排空的超时时间（秒） */
        private long shutdownTimeoutSeconds = 5;
        /** 队列满时的拒绝策略，默认由调用线程执行形成背压 */
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
