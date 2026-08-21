package com.rover.gateway.core.discovery;

import com.rover.common.concurrent.PeriodicTask;
import com.rover.common.model.ServiceInstance;
import com.rover.common.util.ServiceKeys;
import com.rover.common.protocol.QueryResponseBody;
import com.rover.common.spi.discovery.ServiceDiscovery;
import com.rover.nameserver.client.connection.NameserverClient;
import com.rover.nameserver.client.connection.NameserverClientOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-11 10:12:00
 * Description: Nameserver 服务发现：订阅 + 定时对账维护本地实例缓存
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
    /** 拒推后的立即对账使用有界线程池，避免每次拒推都创建新线程。 */
    private final ThreadPoolExecutor forceQueryExecutor;

    /** 指定发现配置构造。 */
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
                .token(settings.getNameserverToken())
                .autoHeartbeat(false)
                .autoReconnect(true)
                .build());
        this.reconcileTask = new PeriodicTask("gateway-nameserver-reconcile");
        this.forceQueryExecutor = new ThreadPoolExecutor(
                1,
                2,
                30,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(128),
                runnable -> {
                    Thread thread = new Thread(runnable, "gateway-force-query");
                    thread.setDaemon(true);
                    return thread;
                },
                (task, executor) -> log.warn(
                        "强制全量对账队列已满或已关闭，丢弃任务: queued={}",
                        executor.getQueue().size()));
    }

    /** 连接 Nameserver、订阅初始服务列表、启动定时对账。 */
    @Override
    public void start() {
        client.start();
        // 推送连续拒绝达阈值 → 异步立刻 query（不对账线程干等一个周期）
        client.getInstanceCache().addForceQueryListener(this::forceQueryAsync);
        for (DiscoverySettings.ServiceSubscribeSpec spec : subscribeServices) {
            subscribeOne(spec.getServiceName(), spec.getGroup());
        }
        reconcileTask.start(this::reconcile, reconcileIntervalMs, reconcileIntervalMs);
        log.info("Nameserver 动态发现已启动, address={}:{}, reconcileIntervalMs={}",
                client.getOptions().getHost(),
                client.getOptions().getPort(),
                reconcileIntervalMs);
    }

    /** 连续拒推后的立即全量拉取；丢到旁路线程，避免堵 Netty IO。 */
    private void forceQueryAsync(String serviceName, String group) {
        try {
            forceQueryExecutor.execute(() -> {
                try {
                    if (!client.isActive()) {
                        return;
                    }
                    client.getInstanceCache().consumeForceQuery(serviceName, group);
                    QueryResponseBody remote = client.query(serviceName, group, false);
                    log.info(
                            "连续拒绝推送后强制全量: serviceName={}, revision={}, epoch={}, size={}",
                            serviceName,
                            remote.getRevision(),
                            remote.getEpoch(),
                            remote.getInstances() == null ? 0 : remote.getInstances().size());
                } catch (Exception ex) {
                    log.warn("强制全量对账失败: serviceName={}", serviceName, ex);
                }
            });
        } catch (RejectedExecutionException ex) {
            log.debug("强制全量线程池已关闭，忽略任务: serviceName={}", serviceName);
        }
    }

    /** 从本地缓存取实例，优先返回健康实例；全不健康时退回全部缓存。 */
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

    /** 路由热更新后补订新服务：已在列表则重订，否则追加并订阅。 */
    @Override
    public void ensureWatch(String serviceName, String group) {
        if (serviceName == null || serviceName.isBlank()) {
            return;
        }
        String key = ServiceKeys.serviceGroup(serviceName, group);
        for (DiscoverySettings.ServiceSubscribeSpec spec : subscribeServices) {
            String existing = ServiceKeys.serviceGroup(spec.getServiceName(), spec.getGroup());
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
        forceQueryExecutor.shutdownNow();
        client.shutdown();
    }

    /** 对单个服务执行 subscribe + query，失败只打 warn 不抛异常。 */
    private void subscribeOne(String serviceName, String group) {
        if (serviceName == null || serviceName.isBlank()) {
            return;
        }
        try {
            client.subscribe(serviceName, group);
            client.query(serviceName, group, false); // 兜底拉取一次
            log.info("已订阅服务: serviceName={}, group={}", serviceName, nullToEmpty(group));
        } catch (Exception ex) {
            log.warn("订阅服务失败，将依赖重连恢复/对账: serviceName={}", serviceName, ex);
        }
    }

    /** 定时对账：强制全量优先，再常规 query；以服务端快照为准覆盖本地。 */
    private void reconcile() {
        if (!client.isActive()) {
            return;
        }
        for (DiscoverySettings.ServiceSubscribeSpec spec : subscribeServices) {
            if (spec.getServiceName() == null || spec.getServiceName().isBlank()) {
                continue;
            }
            try {
                boolean force = client.getInstanceCache()
                        .consumeForceQuery(spec.getServiceName(), spec.getGroup());
                long localRevision = client.getInstanceCache()
                        .revision(spec.getServiceName(), spec.getGroup());
                String localEpoch = client.getInstanceCache()
                        .epoch(spec.getServiceName(), spec.getGroup());
                client.subscribe(spec.getServiceName(), spec.getGroup());
                QueryResponseBody remote = client.query(spec.getServiceName(), spec.getGroup(), false);
                if (force) {
                    log.info(
                            "强制全量对账完成: serviceName={}, localEpoch={}, localRevision={}, "
                                    + "remoteEpoch={}, remoteRevision={}, size={}",
                            spec.getServiceName(),
                            localEpoch,
                            localRevision,
                            remote.getEpoch(),
                            remote.getRevision(),
                            remote.getInstances() == null ? 0 : remote.getInstances().size());
                } else if (remote.getRevision() != localRevision
                        || !java.util.Objects.equals(localEpoch, remote.getEpoch())) {
                    log.info(
                            "对账更新实例: serviceName={}, localEpoch={}, localRevision={}, "
                                    + "remoteEpoch={}, remoteRevision={}, size={}",
                            spec.getServiceName(),
                            localEpoch,
                            localRevision,
                            remote.getEpoch(),
                            remote.getRevision(),
                            remote.getInstances() == null ? 0 : remote.getInstances().size());
                }
            } catch (Exception ex) {
                log.warn("对账失败: serviceName={}", spec.getServiceName(), ex);
            }
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
