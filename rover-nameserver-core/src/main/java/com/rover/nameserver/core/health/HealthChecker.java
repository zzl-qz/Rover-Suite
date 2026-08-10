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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:50:00
 * Description: 定时扫超时实例
 */
@Slf4j
public class HealthChecker {

    private final ServiceRegistry registry;
    private final PushService pushService;
    private final long heartbeatTimeoutMillis;
    private final long checkIntervalMillis;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "nameserver-health-checker");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicBoolean started = new AtomicBoolean(false);

    public HealthChecker(
            ServiceRegistry registry,
            PushService pushService,
            long heartbeatTimeoutMillis,
            long checkIntervalMillis) {
        this.registry = registry;
        this.pushService = pushService;
        this.heartbeatTimeoutMillis = heartbeatTimeoutMillis;
        this.checkIntervalMillis = checkIntervalMillis;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        scheduler.scheduleWithFixedDelay(
                this::safeCheck, checkIntervalMillis, checkIntervalMillis, TimeUnit.MILLISECONDS);
        log.info("健康检查已启动, interval={}ms, heartbeatTimeout={}ms",
                checkIntervalMillis, heartbeatTimeoutMillis);
    }

    public void shutdown() {
        started.set(false);
        scheduler.shutdownNow();
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
        List<InstanceRecord> records = registry.listAllRecords();
        List<RegistrySnapshot> changed = new ArrayList<>();

        for (InstanceRecord record : records) {
            ServiceInstance instance = record.getInstance();
            long idle = now - record.getLastHeartbeatMillis();
            if (idle <= heartbeatTimeoutMillis) {
                continue;
            }

            // 临时实例：超时直接剔除；非临时：先标不健康留在表里
            if (instance.isEphemeral()) {
                RegistrySnapshot snapshot =
                        registry.removeExpired(instance.getServiceName(), instance.getInstanceId());
                if (snapshot != null) {
                    changed.add(snapshot);
                    log.warn("心跳超时，剔除临时实例: {}#{} idle={}ms",
                            instance.getServiceName(), instance.getInstanceId(), idle);
                }
            } else if (instance.isHealthy()) {
                instance.setHealthy(false);
                log.warn("心跳超时，标记实例不健康: {}#{} idle={}ms",
                        instance.getServiceName(), instance.getInstanceId(), idle);
            }
        }

        for (RegistrySnapshot snapshot : changed) {
            pushService.pushSnapshot(snapshot);
        }
    }
}
