package com.rover.agent.runtime.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Micrometer 实现：所有指标写在宿主进程传入的同一个 {@link MeterRegistry} 上，
 * 与其他组件的指标共用一份注册表，避免"同一个进程两套数字"。
 *
 * 指标清单（前缀 {@code rover.agent.}）：
 * <ul>
 *   <li>{@code task.submitted} / {@code task.completed} / {@code task.failed} / {@code task.rejected}：计数；</li>
 *   <li>{@code task.active} / {@code task.queue.size}：当前值（等待输入与终态都不算执行中）；</li>
 *   <li>{@code task.duration}：登记到结束的耗时，标签 status；</li>
 *   <li>{@code model.duration} / {@code model.error}：模型解读耗时与失败次数，标签 model（已规范化）；</li>
 *   <li>{@code sse.connections}：当前打开的事件流连接数。</li>
 * </ul>
 *
 * 标签只允许 {@code status}、{@code reason}、{@code model} 三种，取值都来自有限枚举或规范化后的模型名。
 * 等待输入（WAITING_INPUT）既不算完成也不算失败：它只出现在 task.duration 的 status 标签里，
 * 这样"完成数 + 失败数 + 等待输入数 + 仍在执行数 = 登记数"始终成立。
 */
public final class MicrometerAgentMetrics implements AgentMetrics {

    static final String TASK_SUBMITTED = "rover.agent.task.submitted";
    static final String TASK_COMPLETED = "rover.agent.task.completed";
    static final String TASK_FAILED = "rover.agent.task.failed";
    static final String TASK_REJECTED = "rover.agent.task.rejected";
    static final String TASK_ACTIVE = "rover.agent.task.active";
    static final String TASK_QUEUE_SIZE = "rover.agent.task.queue.size";
    static final String TASK_DURATION = "rover.agent.task.duration";
    static final String MODEL_DURATION = "rover.agent.model.duration";
    static final String MODEL_ERROR = "rover.agent.model.error";
    static final String SSE_CONNECTIONS = "rover.agent.sse.connections";

    private static final String TAG_STATUS = "status";
    private static final String TAG_REASON = "reason";
    private static final String TAG_MODEL = "model";

    /** 模型标签的兜底取值：描述不可用时用它，避免出现空标签值。 */
    static final String UNKNOWN_MODEL = "unknown";

    /** 模型标签长度上限：模型描述来自配置，截断后再入标签，防止异常长的值撑大注册表。 */
    private static final int MAX_MODEL_TAG_LENGTH = 48;

    private final MeterRegistry registry;
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicInteger connections = new AtomicInteger();

    public MicrometerAgentMetrics(MeterRegistry registry) {
        this.registry = registry;
        // 无标签的计数与当前值先注册出来：从未发生过的事件也以 0 出现在注册表里，读数不会时有时无。
        Counter.builder(TASK_SUBMITTED).description("已登记的调查任务数").register(registry);
        Gauge.builder(TASK_ACTIVE, active, AtomicInteger::doubleValue)
                .description("当前执行中的调查任务数").register(registry);
        Gauge.builder(TASK_QUEUE_SIZE, queued, AtomicInteger::doubleValue)
                .description("最近一次采样时的等待队列深度").register(registry);
        Gauge.builder(SSE_CONNECTIONS, connections, AtomicInteger::doubleValue)
                .description("当前打开的任务事件流连接数").register(registry);
    }

    @Override
    public void taskSubmitted() {
        registry.counter(TASK_SUBMITTED).increment();
    }

    @Override
    public void taskStarted() {
        active.incrementAndGet();
    }

    @Override
    public void taskQueued(int queueSize) {
        queued.set(Math.max(0, queueSize));
    }

    @Override
    public void taskSettled(String status, long durationMillis) {
        active.updateAndGet(current -> Math.max(0, current - 1));
        String tag = normalize(status, "UNKNOWN");
        Timer.builder(TASK_DURATION).tag(TAG_STATUS, tag).register(registry)
                .record(Duration.ofMillis(Math.max(0, durationMillis)));
        switch (tag) {
            case "COMPLETED" -> registry.counter(TASK_COMPLETED, TAG_STATUS, tag).increment();
            case "FAILED", "CANCELLED" -> registry.counter(TASK_FAILED, TAG_STATUS, tag).increment();
            default -> {
                // WAITING_INPUT 等静止态：只留耗时，不计入完成或失败。
            }
        }
    }

    @Override
    public void taskRejected(String reason) {
        registry.counter(TASK_REJECTED, TAG_REASON, normalize(reason, "UNKNOWN")).increment();
    }

    @Override
    public void modelCall(String model, long durationMillis, boolean failed) {
        String tag = normalizeModel(model);
        Timer.builder(MODEL_DURATION).tag(TAG_MODEL, tag).register(registry)
                .record(Duration.ofMillis(Math.max(0, durationMillis)));
        if (failed) {
            registry.counter(MODEL_ERROR, TAG_MODEL, tag).increment();
        }
    }

    @Override
    public void sseConnected() {
        connections.incrementAndGet();
    }

    @Override
    public void sseDisconnected() {
        connections.updateAndGet(current -> Math.max(0, current - 1));
    }

    /**
     * 把模型描述规范成标签值：描述形如 {@code "deepseek-chat @ api.deepseek.com"}，
     * 只取模型名部分并统一成小写、只保留模型名允许的字符，
     * 避免同一模型因为写法（大小写、空白、带部署后缀）不同被算成两条线。
     */
    static String normalizeModel(String description) {
        if (description == null) {
            return UNKNOWN_MODEL;
        }
        String name = description.split("@", 2)[0].trim().toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(name.length());
        for (int i = 0; i < name.length() && normalized.length() < MAX_MODEL_TAG_LENGTH; i++) {
            char ch = name.charAt(i);
            boolean allowed = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                    || ch == '.' || ch == '_' || ch == '-' || ch == '/';
            normalized.append(allowed ? ch : '-');
        }
        String value = normalized.toString().replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        return value.isEmpty() ? UNKNOWN_MODEL : value;
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }
}