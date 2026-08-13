package com.rover.common.event;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
 * Description: 事件总线
 */
@Slf4j
public class EventBus {

    /** 事件类型 -> 监听器列表 */
    private final Map<Class<? extends Event>, List<EventListener<?>>> listenerMap = new ConcurrentHashMap<>();

    /** 异步派发线程池 */
    private final ThreadPoolExecutor executor;

    /** 关闭超时（秒） */
    private final long shutdownTimeoutSeconds;

    /** 是否已初始化，防止重复加载 SPI */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /** 是否已关闭：关闭后 publish 直接忽略，避免停机窗口甩 RejectedExecutionException */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** 已发布事件数（异步 + 同步） */
    private final AtomicLong publishCount = new AtomicLong(0);

    /** 监听器执行失败次数 */
    private final AtomicLong dispatchFailCount = new AtomicLong(0);

    /** 关闭后仍有人 publish 的次数 */
    private final AtomicLong ignoredAfterCloseCount = new AtomicLong(0);

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
     * 初始化：SPI 加载 EventListener，反射解析事件类型并注册。
     * 幂等；单个监听器失败只跳过。
     */
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
                Class<? extends Event> eventType = resolveEventType(listener);
                listenerMap.computeIfAbsent(eventType, key -> new CopyOnWriteArrayList<>()).add(listener);
                log.info("SPI 加载事件监听器: {} -> {}", eventType.getSimpleName(), listener.getClass().getName());
            } catch (Exception ex) {
                log.error("加载事件监听器失败，已跳过: {}", listener.getClass().getName(), ex);
            }
        }
    }

    /**
     * 编程式注册监听器（可在 init 前后调用；关闭后拒绝）。
     * 解析泛型事件类型后挂到对应列表。
     */
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
     * 异步发布：不阻塞发布线程。
     * 关闭后忽略；提交被拒时只记日志，不把异常甩回业务线程。
     * 同事件内多个监听器在同一任务里顺序执行；单个失败不影响其余。
     */
    public void publish(Event event) {
        if (closed.get()) {
            ignoredAfterCloseCount.incrementAndGet();
            log.debug("事件总线已关闭，忽略异步发布: {}", event == null ? "null" : event.getClass().getSimpleName());
            return;
        }
        List<EventListener<?>> listeners = match(event);
        if (listeners.isEmpty()) {
            return;
        }
        publishCount.incrementAndGet();
        try {
            executor.execute(() -> dispatchSafely(event, listeners));
        } catch (RejectedExecutionException ex) {
            // 关闭竞态或自定义 AbortPolicy：吞掉，避免打爆 Netty 业务线程
            ignoredAfterCloseCount.incrementAndGet();
            log.warn("异步事件提交被拒绝: event={}", event.getClass().getSimpleName(), ex);
        }
    }

    /**
     * 同步发布：调用线程内顺序派发，保证本轮监听器按注册顺序执行完或失败即停。
     * fail-fast：任一监听器抛异常，立即中断后续监听器，并向上抛 RuntimeException。
     * 不保证多线程全局事件顺序。
     */
    public void publishSync(Event event) {
        if (closed.get()) {
            ignoredAfterCloseCount.incrementAndGet();
            log.debug("事件总线已关闭，忽略同步发布: {}", event == null ? "null" : event.getClass().getSimpleName());
            return;
        }
        List<EventListener<?>> listeners = match(event);
        if (listeners.isEmpty()) {
            return;
        }
        publishCount.incrementAndGet();
        dispatchStrictly(event, listeners);
    }

    /**
     * 匹配监听器：事件具体类 + 其父类（须仍是 Event 体系）。
     * 不匹配“监听父类却漏掉子类”的反向问题已处理；兄弟类型互不影响。
     */
    @SuppressWarnings("unchecked")
    private List<EventListener<?>> match(Event event) {
        if (event == null) {
            return List.of();
        }
        List<EventListener<?>> matched = new ArrayList<>();
        Class<?> type = event.getClass();
        while (type != null && Event.class.isAssignableFrom(type)) {
            List<EventListener<?>> listeners = listenerMap.get(type);
            if (listeners != null && !listeners.isEmpty()) {
                matched.addAll(listeners);
            }
            type = type.getSuperclass();
        }
        // 有人直接监听 Event 标记接口时也能收到
        List<EventListener<?>> rootListeners = listenerMap.get(Event.class);
        if (rootListeners != null && !rootListeners.isEmpty() && !matched.containsAll(rootListeners)) {
            for (EventListener<?> listener : rootListeners) {
                if (!matched.contains(listener)) {
                    matched.add(listener);
                }
            }
        }
        return matched;
    }

    /** 同步 fail-safe：异常不会立刻抛出，而是捕获掉之后继续执行后续的 */
    @SuppressWarnings("unchecked")
    private void dispatchSafely(Event event, List<EventListener<?>> listeners) {
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

    /** 同步 fail-fast：第一个异常立刻抛出，后续监听器不再执行 */
    @SuppressWarnings("unchecked")
    private void dispatchStrictly(Event event, List<EventListener<?>> listeners) {
        for (EventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception ex) {
                dispatchFailCount.incrementAndGet();
                log.error("事件监听器执行失败(fail-fast): event={}, listener={}",
                        event.getClass().getSimpleName(), listener.getClass().getName(), ex);
                throw new RuntimeException(
                        "事件派发失败: event=" + event.getClass().getSimpleName()
                                + ", listener=" + listener.getClass().getName(),
                        ex);
            }
        }
    }

    /** 关闭总线：幂等；之后 publish/register 不再接收 */
    public void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown(); // 优雅关闭

        // 等待任务执行完再强制关闭
        try {
            if (!executor.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            // 如果被打断，就强制中断+恢复断点状态
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** 待派发任务数（异步队列积压） */
    public int getPendingTaskCount() {
        return executor.getQueue().size();
    }

    public long getPublishCount() {
        return publishCount.get();
    }

    public long getDispatchFailCount() {
        return dispatchFailCount.get();
    }

    public long getIgnoredAfterCloseCount() {
        return ignoredAfterCloseCount.get();
    }

    private static ThreadFactory threadFactory(String name) {
        AtomicInteger seq = new AtomicInteger(1);
        return r -> {
            Thread thread = new Thread(r, "rover-event-bus-" + name + "-" + seq.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * 反射解析监听器监听的事件类型。
     * 支持：直接实现 EventListener&lt;T&gt;、泛型父类、中间接口 extends EventListener&lt;T&gt;。
     */
    @SuppressWarnings("unchecked")
    static Class<? extends Event> resolveEventType(EventListener<?> listener) {
        Class<?> eventType = resolveEventType(listener.getClass(), Collections.emptyMap());
        if (eventType == null || !Event.class.isAssignableFrom(eventType)) {
            throw new IllegalArgumentException("无法解析监听器的事件类型: " + listener.getClass().getName());
        }
        return (Class<? extends Event>) eventType;
    }

    private static Class<?> resolveEventType(Class<?> clazz, Map<TypeVariable<?>, Type> typeVariables) {
        if (clazz == null || clazz == Object.class) {
            return null;
        }

        // 本类声明的泛型接口（含 FooListener extends EventListener<Foo> 这种中间接口）
        for (Type genericInterface : clazz.getGenericInterfaces()) {
            Class<?> resolved = resolveFromType(genericInterface, typeVariables);
            if (resolved != null) {
                return resolved;
            }
        }

        // 父类链
        Type genericSuperclass = clazz.getGenericSuperclass();
        if (genericSuperclass != null) {
            Class<?> resolved = resolveFromType(genericSuperclass, typeVariables);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private static Class<?> resolveFromType(Type type, Map<TypeVariable<?>, Type> typeVariables) {
        if (type instanceof ParameterizedType parameterizedType) {
            Type raw = parameterizedType.getRawType();
            if (raw == EventListener.class) {
                return toClass(parameterizedType.getActualTypeArguments()[0], typeVariables);
            }
            if (raw instanceof Class<?> rawClass) {
                Map<TypeVariable<?>, Type> next = mapTypeVariables(rawClass, parameterizedType, typeVariables);
                return resolveEventType(rawClass, next);
            }
            return null;
        }
        if (type instanceof Class<?> clazz) {
            // class X implements FooListener（裸接口）→ 继续进接口层次
            return resolveEventType(clazz, typeVariables);
        }
        return null;
    }

    /**
     * 把类声明时的 class Child<T> 中的 T，翻译成实际调用时的类型，并记录下来
     */
    private static Map<TypeVariable<?>, Type> mapTypeVariables(
            Class<?> rawClass, ParameterizedType parameterizedType, Map<TypeVariable<?>, Type> parent) {
        Map<TypeVariable<?>, Type> next = new HashMap<>(parent);
        TypeVariable<?>[] vars = rawClass.getTypeParameters();
        Type[] args = parameterizedType.getActualTypeArguments();
        for (int i = 0; i < vars.length && i < args.length; i++) {
            next.put(vars[i], args[i]);
        }
        return next;
    }

    /**
     * 解析出最原始的class类型
     */
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
     * 默认队列满时 CallerRunsPolicy
     */
    public static class Config {

        private int corePoolSize = 2;
        private int maxPoolSize = 10;
        private int queueCapacity = 1024;
        private long keepAliveSeconds = 60;
        private long shutdownTimeoutSeconds = 5;

        // 退换给调用者
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
