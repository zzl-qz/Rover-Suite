package com.rover.nameserver.core.health;

import com.rover.common.model.ServiceInstance;
import com.rover.nameserver.core.model.InstanceRecord;
import com.rover.nameserver.core.push.PushService;
import com.rover.nameserver.core.registry.RegistrySnapshot;
import com.rover.nameserver.core.registry.ServiceRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:50:00
 * Description: 定时扫超时实例，参数可热更新
 *
 * 核心职责：用一个独立守护线程按固定间隔扫描注册表全部实例，按两类规则处理：</p>
 * <ul>
 *     <li><b>临时实例（ephemeral）</b>：静默时间超过 {@code instanceExpireMillis}
 *     直接剔除出注册表，并向订阅方推送变更；</li>
 *     <li><b>非临时实例</b>：静默时间超过 {@code heartbeatTimeoutMillis}
 *     先标记为不健康（不下线），继续保留供查询。</li>
 * </ul>
 *
 * 被 NameserverTcpServer 创建并启动/关闭；健康检查间隔、心跳超时、过期时间
 * 三个参数经 {@link com.rover.nameserver.core.config.NameserverRuntimeConfigApplier}
 * 热更新（其中「改间隔」会重新排定调度任务）。依赖 {@link ServiceRegistry} 读取记录、
 * {@link PushService} 广播剔除引发的变更。</p>
 *
 * 时间语义说明：实例的「心跳年龄」idle = now - lastHeartbeatMillis，
 * 由 {@link com.rover.nameserver.core.model.InstanceRecord#touchHeartbeat()}
 * 每次收到心跳时刷新；健康检查仅负责消费该时间做判定，不修改注册表（除剔除外）。</p>
 */
@Slf4j
public class HealthChecker {

    /** 注册表引用，用于枚举全部实例记录与执行剔除 */
    private final ServiceRegistry registry;
    /** 推送服务，剔除实例后广播包含新 revision 的快照 */
    private final PushService pushService;
    /** 心跳超时时间(ms)：非临时实例超过即标记不健康；AtomicLong 支持热更新 */
    private final AtomicLong heartbeatTimeoutMillis;
    /** 检查间隔(ms)：每次扫描的周期；AtomicLong 支持热更新 */
    private final AtomicLong checkIntervalMillis;
    /** 临时实例过期时间(ms)：超过直接剔除；AtomicLong 支持热更新 */
    private final AtomicLong instanceExpireMillis;
    /** 单线程调度器：串行执行检查，天然避免多线程扫描的并发问题；守护线程随 JVM 退出 */
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "nameserver-health-checker");
                thread.setDaemon(true);
                return thread;
            });
    /** 是否已启动，保证 start() 幂等（并发安全） */
    private final AtomicBoolean started = new AtomicBoolean(false);
    /** 当前排定的调度任务句柄，用于热更新间隔时取消旧任务重排 */
    private volatile ScheduledFuture<?> future;

    /**
     * 构造健康检查器。
     *
     * @param registry                 注册表（读取 + 剔除）
     * @param pushService              推送服务（广播剔除变更）
     * @param heartbeatTimeoutMillis   初始心跳超时(ms)
     * @param checkIntervalMillis      初始检查间隔(ms)
     * @param instanceExpireMillis     初始临时实例过期时间(ms)
     */
    public HealthChecker(
            ServiceRegistry registry,
            PushService pushService,
            long heartbeatTimeoutMillis,
            long checkIntervalMillis,
            long instanceExpireMillis) {
        this.registry = registry;
        this.pushService = pushService;
        this.heartbeatTimeoutMillis = new AtomicLong(heartbeatTimeoutMillis);
        this.checkIntervalMillis = new AtomicLong(checkIntervalMillis);
        this.instanceExpireMillis = new AtomicLong(instanceExpireMillis);
    }

    public long getHeartbeatTimeoutMillis() {
        return heartbeatTimeoutMillis.get();
    }

    public long getCheckIntervalMillis() {
        return checkIntervalMillis.get();
    }

    public long getInstanceExpireMillis() {
        return instanceExpireMillis.get();
    }

    /** 热更新心跳超时时间；必须为正数 */
    public void setHeartbeatTimeoutMillis(long value) {
        requirePositive(value, "heartbeatTimeoutMillis");
        this.heartbeatTimeoutMillis.set(value);
    }

    /** 热更新临时实例过期时间；必须为正数 */
    public void setInstanceExpireMillis(long value) {
        requirePositive(value, "instanceExpireMillis");
        this.instanceExpireMillis.set(value);
    }

    /** 改间隔要重排调度 */
    public synchronized void setCheckIntervalMillis(long value) {
        requirePositive(value, "checkIntervalMillis");
        this.checkIntervalMillis.set(value);
        // 已启动时新间隔不等到下轮自然生效，而是立刻取消旧任务按新间隔重排
        if (started.get()) {
            schedule(value);
            log.info("健康检查间隔已热更新: {}ms", value);
        }
    }

    /**
     * 启动健康检查（幂等）：首次调用后按当前间隔排定周期任务。
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        schedule(checkIntervalMillis.get());
        log.info("健康检查已启动, interval={}ms, heartbeatTimeout={}ms, expire={}ms",
                checkIntervalMillis.get(), heartbeatTimeoutMillis.get(), instanceExpireMillis.get());
    }

    /**
     * 关闭健康检查：停止已排定任务并销毁调度线程；可安全重复调用。
     */
    public void shutdown() {
        started.set(false);
        ScheduledFuture<?> current = future;
        if (current != null) {
            // 不中断正在执行的一轮扫描，只取消后续周期
            current.cancel(false);
        }
        scheduler.shutdownNow();
    }

    /**
     * 按给定间隔排定周期扫描任务；若已有任务先取消（供间隔热更新/重启用）。
     * 使用 scheduleWithFixedDelay：上一轮结束再等一个间隔，避免扫描耗时引起的任务堆积。
     */
    private void schedule(long intervalMs) {
        ScheduledFuture<?> current = future;
        if (current != null) {
            current.cancel(false);
        }
        future = scheduler.scheduleWithFixedDelay(
                this::safeCheck, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /** 包装一次扫描：任何异常只记日志，保证调度任务不因单轮失败而中断 */
    private void safeCheck() {
        try {
            checkOnce();
        } catch (Exception ex) {
            log.warn("健康检查执行失败", ex);
        }
    }

    /**
     * 执行一轮扫描（包级可见，便于测试直接触发）。
     * 时间判定逻辑：对每条记录计算 idle = now - lastHeartbeatMillis，
     * 临时实例超 expire 剔除、非临时实例超 heartbeatTimeout 标不健康；
     * 最后统一推送本轮被剔除实例的快照（保证并发下每实例最多推一次）。
     */
    void checkOnce() {
        long now = System.currentTimeMillis();
        long heartbeatTimeout = heartbeatTimeoutMillis.get();
        long expire = instanceExpireMillis.get();
        // 先快照全部记录再逐条判定，避免扫描期间注册/注销导致漏扫或重复处理
        List<InstanceRecord> records = registry.listAllRecords();
        List<RegistrySnapshot> changed = new ArrayList<>();

        for (InstanceRecord record : records) {
            ServiceInstance instance = record.getInstance();
            // 心跳年龄：距上次心跳的毫秒数，是超时判定的唯一依据
            long idle = now - record.getLastHeartbeatMillis();

            if (instance.isEphemeral()) {
                // 临时实例：超过 expire 直接剔除
                if (idle <= expire) {
                    continue;
                }
                // removeExpired 返回 null 表示已被并发注销，无需重复推送
                RegistrySnapshot snapshot =
                        registry.removeExpired(instance.getServiceName(), instance.getInstanceId());
                if (snapshot != null) {
                    changed.add(snapshot);
                    log.warn("心跳超时，剔除临时实例: {}#{} idle={}ms expire={}ms",
                            instance.getServiceName(), instance.getInstanceId(), idle, expire);
                }
                continue;
            }

            // 非临时：超过心跳超时先标不健康
            if (idle <= heartbeatTimeout) {
                continue;
            }
            // 标记健康状态变化需要 isHealthy 判断，避免每轮重复打日志
            if (instance.isHealthy()) {
                instance.setHealthy(false);
                log.warn("心跳超时，标记实例不健康: {}#{} idle={}ms",
                        instance.getServiceName(), instance.getInstanceId(), idle);
            }
        }

        // 剔除完成后再统一推送，减少推送次数且保证推送内容为最终状态
        for (RegistrySnapshot snapshot : changed) {
            pushService.pushSnapshot(snapshot);
        }
    }

    /** 校验热更新参数为正数；否则抛 IllegalArgumentException */
    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " 必须大于 0");
        }
    }
}