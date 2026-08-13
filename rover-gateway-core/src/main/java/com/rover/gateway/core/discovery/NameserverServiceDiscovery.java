package com.rover.gateway.core.discovery;

import com.rover.common.concurrent.PeriodicTask;
import com.rover.common.model.ServiceInstance;
import com.rover.common.protocol.QueryResponseBody;
import com.rover.common.spi.ServiceDiscovery;
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
 *
 * 这个类是什么：ServiceDiscovery 的 Nameserver 实现，维护本地实例缓存。
 * 核心职责：①启动时连接 Nameserver 并订阅配置里的服务；②getInstances 读本地缓存并过滤健康实例；
 * ③路由热更新时 ensureWatch 补订；④定时对账防止推送丢失。
 * 被谁用：GatewayHttpServer 在 discovery.type=NAMESERVER 时创建；RouteAndProxyFilter 查实例。
 */
@Slf4j
public class NameserverServiceDiscovery implements ServiceDiscovery {

    /** Nameserver 长连接客户端，负责订阅和 query。 */
    private final NameserverClient client;

    /** 当前已订阅/待订阅的服务列表，路由热更新时会追加。 */
    private final CopyOnWriteArrayList<DiscoverySettings.ServiceSubscribeSpec> subscribeServices;

    /** 定时对账间隔（毫秒），下限 1000ms。 */
    private final long reconcileIntervalMs;

    /** 后台对账任务，周期性 query 并比对 revision。 */
    private final PeriodicTask reconcileTask;

    /**
     * @param settings 发现配置，含 Nameserver 地址、订阅列表、对账间隔
     */
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

    /** 连接 Nameserver、订阅初始服务列表、启动定时对账。 */
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

    /**
     * 从本地缓存取实例，优先返回健康实例；全不健康时退回全部缓存。
     *
     * @param serviceName 服务名
     * @param group       分组
     * @return 可用实例列表，缓存为空时返回空列表
     */
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

    /**
     * 路由热更新后补订新服务：已在列表里则重新 subscribe，否则追加并订阅。
     *
     * @param serviceName 服务名
     * @param group       分组
     */
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

    /** 停止对账任务并关闭 Nameserver 客户端。 */
    @Override
    public void close() {
        reconcileTask.stop();
        client.shutdown();
    }

    /** 对单个服务执行 subscribe + query，失败只打 warn 不抛异常。 */
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

    /** 定时对账：比对本地 revision 和远端，有差异时打 info 日志。 */
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
