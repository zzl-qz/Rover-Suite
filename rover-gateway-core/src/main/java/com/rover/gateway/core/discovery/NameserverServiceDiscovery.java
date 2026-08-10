package com.rover.gateway.core.discovery;

import com.rover.common.concurrent.PeriodicTask;
import com.rover.common.model.ServiceInstance;
import com.rover.common.protocol.QueryResponseBody;
import com.rover.nameserver.client.connection.NameserverClient;
import com.rover.nameserver.client.connection.NameserverClientOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:20:00
 * Description: 通过 Nameserver 订阅 + 定时对账维护本地实例
 */
@Slf4j
public class NameserverServiceDiscovery implements ServiceDiscovery {

    private final NameserverClient client;
    private final CopyOnWriteArrayList<DiscoverySettings.ServiceSubscribeSpec> subscribeServices;
    private final long reconcileIntervalMs;
    private final PeriodicTask reconcileTask;

    public NameserverServiceDiscovery(DiscoverySettings settings) {
        Objects.requireNonNull(settings, "settings");
        this.subscribeServices = new CopyOnWriteArrayList<>(
                settings.getSubscribeServices() == null
                        ? List.of()
                        : settings.getSubscribeServices());
        this.reconcileIntervalMs = Math.max(1000L, settings.getReconcileIntervalMs());
        this.client = new NameserverClient(NameserverClientOptions.builder()
                .host(settings.getNameserverHost())
                .port(settings.getNameserverPort())
                .autoHeartbeat(false)
                .autoReconnect(true)
                .build());
        this.reconcileTask = new PeriodicTask("gateway-nameserver-reconcile");
    }

    @Override
    public void start() {
        client.start();
        for (DiscoverySettings.ServiceSubscribeSpec spec : subscribeServices) {
            subscribeOne(spec.getServiceName(), spec.getGroup());
        }
        reconcileTask.start(this::reconcile, reconcileIntervalMs, reconcileIntervalMs);
        log.info("Nameserver 动态发现已启动, address={}:{}, reconcileIntervalMs={}",
                client.getOptions().getHost(),
                client.getOptions().getPort(),
                reconcileIntervalMs);
    }

    @Override
    public List<ServiceInstance> getInstances(String serviceName, String group) {
        List<ServiceInstance> cached = client.getCachedInstances(serviceName, group);
        if (cached.isEmpty()) {
            return List.of();
        }
        List<ServiceInstance> healthy = new ArrayList<>(cached.size());
        for (ServiceInstance instance : cached) {
            if (instance != null && instance.isHealthy()) {
                healthy.add(instance);
            }
        }
        return healthy.isEmpty() ? cached : healthy;
    }

    @Override
    public void ensureWatch(String serviceName, String group) {
        if (serviceName == null || serviceName.isBlank()) {
            return;
        }
        String key = serviceName + "#" + nullToEmpty(group);
        for (DiscoverySettings.ServiceSubscribeSpec spec : subscribeServices) {
            String existing = spec.getServiceName() + "#" + nullToEmpty(spec.getGroup());
            if (key.equals(existing)) {
                subscribeOne(serviceName, group);
                return;
            }
        }
        DiscoverySettings.ServiceSubscribeSpec spec = new DiscoverySettings.ServiceSubscribeSpec();
        spec.setServiceName(serviceName);
        spec.setGroup(group);
        subscribeServices.add(spec);
        subscribeOne(serviceName, group);
    }

    @Override
    public void close() {
        reconcileTask.stop();
        client.shutdown();
    }

    private void subscribeOne(String serviceName, String group) {
        if (serviceName == null || serviceName.isBlank()) {
            return;
        }
        try {
            client.subscribe(serviceName, group);
            client.query(serviceName, group, false);
            log.info("已订阅服务: serviceName={}, group={}", serviceName, nullToEmpty(group));
        } catch (Exception ex) {
            log.warn("订阅服务失败，将依赖重连恢复/对账: serviceName={}", serviceName, ex);
        }
    }

    private void reconcile() {
        if (!client.isActive()) {
            return;
        }
        for (DiscoverySettings.ServiceSubscribeSpec spec : subscribeServices) {
            if (spec.getServiceName() == null || spec.getServiceName().isBlank()) {
                continue;
            }
            try {
                long localRevision = client.getInstanceCache().revision(spec.getServiceName(), spec.getGroup());
                client.subscribe(spec.getServiceName(), spec.getGroup());
                QueryResponseBody remote = client.query(spec.getServiceName(), spec.getGroup(), false);
                if (remote.getRevision() != localRevision) {
                    log.info("对账更新实例: serviceName={}, localRevision={}, remoteRevision={}, size={}",
                            spec.getServiceName(),
                            localRevision,
                            remote.getRevision(),
                            remote.getInstances() == null ? 0 : remote.getInstances().size());
                }
            } catch (Exception ex) {
                log.debug("对账失败: serviceName={}, msg={}", spec.getServiceName(), ex.getMessage());
            }
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
