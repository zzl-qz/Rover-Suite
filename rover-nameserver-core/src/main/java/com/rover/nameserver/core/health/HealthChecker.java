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
 * Created: 2026-08-07 14:10:00
 * Description: 定时扫描注册表：临时实例超时剔除并推送、非临时实例超时标记不健康，参数可热更新
 */
@Slf4j
public class HealthChecker {

    private final ServiceRegistry registry;
    private final PushService pushService;
    private final AtomicLong heartbeatTimeoutMillis;
    private final AtomicLong checkIntervalMillis;
    private final AtomicLong instanceExpireMillis;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "nameserver-health-checker");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> future;

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

    public void setHeartbeatTimeoutMillis(long value) {
        requirePositive(value, "heartbeatTimeoutMillis");
        this.heartbeatTimeoutMillis.set(value);
    }

    public void setInstanceExpireMillis(long value) {
        requirePositive(value, "instanceExpireMillis");
        this.instanceExpireMillis.set(value);
    }

    public synchronized void setCheckIntervalMillis(long value) {
        requirePositive(value, "checkIntervalMillis");
        this.checkIntervalMillis.set(value);
        if (started.get()) {
            schedule(value);
        }
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        schedule(checkIntervalMillis.get());
    }

    public void shutdown() {
        started.set(false);
        ScheduledFuture<?> current = future;
        if (current != null) {
            current.cancel(false);
        }
        scheduler.shutdownNow();
    }

    private void schedule(long intervalMs) {
        ScheduledFuture<?> previous = future;
        if (previous != null) {
            previous.cancel(false);
        }
        future = scheduler.scheduleWithFixedDelay(
                this::safeCheck, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void safeCheck() {
        try {
            checkOnce();
        } catch (Exception ex) {
            log.warn("健康检查执行失败", ex);
        }
    }

    void checkOnce() {
        long now = System.currentTimeMillis();
        long heartbeatTimeout = heartbeatTimeoutMillis.get();
        long expire = instanceExpireMillis.get();
        List<InstanceRecord> records = registry.listAllRecords();
        List<RegistrySnapshot> changed = new ArrayList<>();

        for (InstanceRecord record : records) {
            ServiceInstance instance = record.getInstance();
            long idle = now - record.getLastHeartbeatMillis();

            if (instance.isEphemeral()) {
                if (idle <= expire) {
                    continue;
                }
                RegistrySnapshot snapshot =
                        registry.removeExpired(instance.getServiceName(), instance.getInstanceId());
                if (snapshot != null) {
                    changed.add(snapshot);
                    log.warn("心跳超时，剔除临时实例: {}#{} idle={}ms expire={}ms",
                            instance.getServiceName(), instance.getInstanceId(), idle, expire);
                }
                continue;
            }

            if (idle <= heartbeatTimeout) {
                continue;
            }
            if (instance.isHealthy()) {
                instance.setHealthy(false);
                log.warn("心跳超时，标记实例不健康: {}#{} idle={}ms",
                        instance.getServiceName(), instance.getInstanceId(), idle);
            }
        }

        for (RegistrySnapshot snapshot : changed) {
            pushService.pushSnapshot(snapshot);
        }
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " 必须大于 0");
        }
    }
}
