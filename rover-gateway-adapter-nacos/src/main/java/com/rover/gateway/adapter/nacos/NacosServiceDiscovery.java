package com.rover.gateway.adapter.nacos;

import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.rover.common.model.ServiceInstance;
import com.rover.common.spi.discovery.ServiceDiscovery;
import com.rover.common.spi.discovery.ServiceDiscoveryStatus;
import com.rover.common.util.ServiceKeys;
import com.rover.gateway.core.discovery.DiscoverySettings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import lombok.extern.slf4j.Slf4j;

/**
 * 基于 Nacos Naming 的服务发现实现。
 * 启动时订阅路由涉及的服务，并用 Nacos 事件更新本地快照；请求线程只读取快照，
 * 不直接访问 Nacos。该类只负责服务发现，不负责向 Nacos 注册实例。
 */
@Slf4j
public final class NacosServiceDiscovery implements ServiceDiscovery, ServiceDiscoveryStatus {

    private final DiscoverySettings settings;
    private final NacosClientFactory clientFactory;
    private final CopyOnWriteArrayList<DiscoverySettings.ServiceSubscribeSpec> subscribeServices;
    private final Map<String, List<ServiceInstance>> snapshots = new ConcurrentHashMap<>();
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final LongAdder subscribeFailures = new LongAdder();
    private final LongAdder snapshotUpdates = new LongAdder();
    private final AtomicLong lastSnapshotUpdatedAtMillis = new AtomicLong();
    private volatile NacosClient client;

    /** 使用 Gateway 的 Nacos 配置创建发现客户端，构造阶段不建立网络连接。 */
    public NacosServiceDiscovery(DiscoverySettings settings) {
        this(settings, SdkNacosClient::create);
    }

    /** 测试用构造：注入客户端工厂，避免单元测试依赖真实 Nacos。 */
    NacosServiceDiscovery(DiscoverySettings settings, NacosClientFactory clientFactory) {
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
        this.clientFactory = java.util.Objects.requireNonNull(clientFactory, "clientFactory");
        this.subscribeServices = new CopyOnWriteArrayList<>(
                settings.getSubscribeServices() == null ? List.of() : settings.getSubscribeServices());
    }

    /** 连接 Nacos，并订阅启动时配置的服务。重复启动不会重复创建客户端。 */
    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        try {
            client = clientFactory.create(properties(settings));
            for (DiscoverySettings.ServiceSubscribeSpec spec : subscribeServices) {
                subscribeOne(spec.getServiceName(), spec.getGroup());
            }
            log.info("Nacos 服务发现已启动, serverAddr={}, namespace={}",
                    settings.getProviderProperties().get("serverAddr"),
                    settings.getProviderProperties().get("namespace"));
        } catch (Exception ex) {
            started.set(false);
            closeNamingService();
            throw new IllegalStateException("Nacos 服务发现启动失败", ex);
        }
    }

    /** 返回指定服务的本地快照；Nacos 暂时不可用时保留最近一次结果。 */
    @Override
    public List<ServiceInstance> getInstances(String serviceName, String group) {
        List<ServiceInstance> instances = snapshots.get(ServiceKeys.serviceGroup(serviceName, group));
        if (instances == null || instances.isEmpty()) {
            return List.of();
        }
        List<ServiceInstance> copy = new ArrayList<>(instances.size());
        for (ServiceInstance instance : instances) {
            copy.add(copyOf(instance));
        }
        return Collections.unmodifiableList(copy);
    }

    /** 动态追加服务订阅；已订阅的 service/group 不会重复订阅。 */
    @Override
    public synchronized void ensureWatch(String serviceName, String group) {
        if (serviceName == null || serviceName.isBlank()) {
            return;
        }
        String key = ServiceKeys.serviceGroup(serviceName, group);
        for (DiscoverySettings.ServiceSubscribeSpec spec : subscribeServices) {
            if (key.equals(ServiceKeys.serviceGroup(spec.getServiceName(), spec.getGroup()))) {
                return;
            }
        }
        DiscoverySettings.ServiceSubscribeSpec spec = new DiscoverySettings.ServiceSubscribeSpec();
        spec.setServiceName(serviceName);
        spec.setGroup(group);
        subscribeServices.add(spec);
        if (started.get()) {
            subscribeOne(serviceName, group);
        }
    }

    /** 订阅一个 Nacos 服务，并立即拉取一次当前实例列表作为初始快照。 */
    private void subscribeOne(String serviceName, String group) {
        if (serviceName == null || serviceName.isBlank() || client == null) {
            return;
        }
        String key = ServiceKeys.serviceGroup(serviceName, group);
        try {
            EventListener listener = event -> {
                if (event instanceof NamingEvent namingEvent) {
                    replace(key, group, namingEvent.getInstances());
                }
            };
            client.subscribe(serviceName, groupOrDefault(group), listener);
            subscriptions.put(key, new Subscription(serviceName, groupOrDefault(group), listener));
            replace(key, group, client.getAllInstances(serviceName, groupOrDefault(group)));
        } catch (Exception ex) {
            subscribeFailures.increment();
            log.warn("Nacos 订阅服务失败: serviceName={}, group={}", serviceName, group, ex);
        }
    }

    /** 将 Nacos 实例列表转换成 Gateway 统一的 ServiceInstance 快照并原子替换。 */
    private void replace(String key, String group, List<Instance> instances) {
        if (instances == null || instances.isEmpty()) {
            snapshots.put(key, List.of());
            snapshotUpdates.increment();
            lastSnapshotUpdatedAtMillis.set(System.currentTimeMillis());
            return;
        }
        List<ServiceInstance> mapped = new ArrayList<>(instances.size());
        for (Instance source : instances) {
            if (source == null) {
                continue;
            }
            ServiceInstance target = new ServiceInstance();
            target.setServiceName(source.getServiceName());
            target.setHost(source.getIp());
            target.setPort(source.getPort());
            target.setInstanceId(source.getInstanceId());
            target.setHealthy(source.isHealthy() && source.isEnabled());
            target.setWeight((int) Math.max(1, Math.min(Integer.MAX_VALUE, Math.round(source.getWeight()))));
            target.setGroup(group);
            target.setZone(source.getClusterName());
            target.setEphemeral(source.isEphemeral());
            target.setMetadata(source.getMetadata() == null ? Map.of() : Map.copyOf(source.getMetadata()));
            mapped.add(target);
        }
        snapshots.put(key, Collections.unmodifiableList(mapped));
        snapshotUpdates.increment();
        lastSnapshotUpdatedAtMillis.set(System.currentTimeMillis());
    }

    /** 返回 Nacos 连接、订阅和快照状态，供 Gateway Admin 与指标导出使用。 */
    @Override
    public Map<String, Object> status() {
        Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("started", started.get());
        status.put("connected", client != null && client.isConnected());
        status.put("subscriptions", subscriptions.size());
        status.put("subscribeFailures", subscribeFailures.sum());
        status.put("snapshotUpdates", snapshotUpdates.sum());
        status.put("lastSnapshotUpdatedAtMillis", lastSnapshotUpdatedAtMillis.get());
        return Collections.unmodifiableMap(status);
    }

    /** 将 Gateway Nacos 配置转换为 Nacos SDK 所需的 Properties。 */
    private static Properties properties(DiscoverySettings settings) {
        Map<String, String> nacos = settings.getProviderProperties();
        Properties properties = new Properties();
        properties.setProperty("serverAddr", required(nacos.get("serverAddr"), "serverAddr"));
        putIfPresent(properties, "namespace", nacos.get("namespace"));
        putIfPresent(properties, "username", nacos.get("username"));
        putIfPresent(properties, "password", nacos.get("password"));
        long timeoutMs = parseLong(nacos.get("timeoutMs"), 3000);
        if (timeoutMs > 0) {
            properties.setProperty("namingRequestTimeout", Long.toString(timeoutMs));
        }
        return properties;
    }

    /** 将空 group 映射为 Nacos 默认分组。 */
    private static String groupOrDefault(String group) {
        return group == null || group.isBlank() ? "DEFAULT_GROUP" : group;
    }

    /** 校验必填的 Nacos 配置项。 */
    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Nacos " + name + " 不能为空");
        }
        return value.trim();
    }

    /** 读取 provider 配置中的长整数，非法值交给启动校验或使用默认值。 */
    private static long parseLong(String value, long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    /** 仅把非空配置写入 Nacos 属性，避免覆盖 SDK 默认值。 */
    private static void putIfPresent(Properties properties, String key, String value) {
        if (value != null && !value.isBlank()) {
            properties.setProperty(key, value.trim());
        }
    }

    /** 复制统一实例对象，避免调用方修改本地发现快照。 */
    private static ServiceInstance copyOf(ServiceInstance source) {
        ServiceInstance copy = new ServiceInstance();
        copy.setServiceName(source.getServiceName());
        copy.setHost(source.getHost());
        copy.setPort(source.getPort());
        copy.setInstanceId(source.getInstanceId());
        copy.setRegisterTime(source.getRegisterTime());
        copy.setHealthy(source.isHealthy());
        copy.setWeight(source.getWeight());
        copy.setGroup(source.getGroup());
        copy.setZone(source.getZone());
        copy.setEphemeral(source.isEphemeral());
        copy.setMetadata(source.getMetadata() == null ? Map.of() : Map.copyOf(source.getMetadata()));
        return copy;
    }

    /** 停止 Nacos 客户端并清理本地快照与订阅状态。 */
    @Override
    public void close() {
        started.set(false);
        closeNamingService();
        snapshots.clear();
        subscriptions.clear();
    }

    /** 安全关闭当前 Nacos 客户端，允许重复调用。 */
    private void closeNamingService() {
        NacosClient current = client;
        client = null;
        if (current != null) {
            try {
                for (Subscription subscription : subscriptions.values()) {
                    current.unsubscribe(subscription.serviceName(), subscription.group(), subscription.listener());
                }
                current.close();
            } catch (Exception ex) {
                log.warn("关闭 Nacos 服务发现失败", ex);
            }
        }
    }

    /** 保存取消订阅所需的原始服务名、分组和监听器。 */
    private record Subscription(String serviceName, String group, EventListener listener) {
    }
}
