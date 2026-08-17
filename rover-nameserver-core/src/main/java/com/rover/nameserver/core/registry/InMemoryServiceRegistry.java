package com.rover.nameserver.core.registry;

import com.rover.common.model.ServiceInstance;
import com.rover.common.protocol.RegisterRequest;
import com.rover.nameserver.core.model.InstanceRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-02 15:50:00
 * Description: 服务注册表单机内存实现：双层 ConcurrentHashMap 无锁安全读写，每个服务维护单调递增 revision
 */
@Slf4j
public class InMemoryServiceRegistry implements ServiceRegistry {

    private static final int LOCK_STRIPES = 256;
    private final Object[] serviceLocks = createServiceLocks();

    /** serviceName -> (instanceId -> 记录) */
    private final Map<String, Map<String, InstanceRecord>> services = new ConcurrentHashMap<>();
    /** 每个服务自己的版本号，变更时 +1 */
    private final Map<String, AtomicLong> revisions = new ConcurrentHashMap<>();

    /** 注册实例（同 instanceId 覆盖旧记录），版本号 +1 并返回新快照。 */
    @Override
    public RegistrySnapshot register(RegisterRequest request) {
        InstanceRecord record = InstanceRecord.from(request);
        String serviceName = record.getInstance().getServiceName();
        String instanceId = record.getInstance().getInstanceId();

        synchronized (lockFor(serviceName)) {
            // 和外层注销走同一把 compute，避免「空了就摘」把刚 put 进去的新实例一起摘掉
            services.compute(serviceName, (key, instances) -> {
                Map<String, InstanceRecord> map =
                        instances == null ? new ConcurrentHashMap<>() : instances;
                map.put(instanceId, record);
                return map;
            });
            long revision = bumpRevision(serviceName);
            log.info("实例注册成功: {}#{} revision={}", serviceName, instanceId, revision);
            return snapshotOf(serviceName, record.getInstance().getGroup(), revision);
        }
    }

    /** 注销实例，版本号 +1 并返回新快照；实例不存在时返回 null（调用方无需推送）。 */
    @Override
    public RegistrySnapshot unregister(String serviceName, String instanceId) {
        synchronized (lockFor(serviceName)) {
            InstanceRecord[] removed = new InstanceRecord[1];
            services.computeIfPresent(serviceName, (key, current) -> {
                removed[0] = current.remove(instanceId);
                // 空了就摘掉这个 key；和 register 的 compute 互斥，不会误删刚注册的实例
                return current.isEmpty() ? null : current;
            });
            if (removed[0] == null) {
                return null;
            }
            long revision = bumpRevision(serviceName);
            log.info("实例注销: {}#{} revision={}", serviceName, instanceId, revision);
            return snapshotOf(serviceName, removed[0].getInstance().getGroup(), revision);
        }
    }

    /**
     * 处理一次心跳：刷新最近心跳时间；若从不健康恢复则 bump revision。
     */
    @Override
    public HeartbeatResult heartbeat(String serviceName, String instanceId) {
        synchronized (lockFor(serviceName)) {
            InstanceRecord record = find(serviceName, instanceId);
            if (record == null) {
                return HeartbeatResult.notFound();
            }
            boolean recovered = record.touchHeartbeat();
            if (!recovered) {
                return HeartbeatResult.touched();
            }
            long revision = bumpRevision(serviceName);
            log.info("实例心跳恢复健康: {}#{} revision={}", serviceName, instanceId, revision);
            return HeartbeatResult.recovered(
                    snapshotOf(serviceName, record.getInstance().getGroup(), revision));
        }
    }

    /**
     * 标记实例不健康：仅在此前为健康时 bump revision 并返回快照。
     */
    @Override
    public RegistrySnapshot markUnhealthy(
            String serviceName, String instanceId, long heartbeatDeadlineMillis) {
        synchronized (lockFor(serviceName)) {
            InstanceRecord record = find(serviceName, instanceId);
            if (record == null) {
                return null;
            }
            ServiceInstance instance = record.getInstance();
            if (instance == null || !record.markUnhealthyIfExpired(heartbeatDeadlineMillis)) {
                return null;
            }
            long revision = bumpRevision(serviceName);
            log.info("实例标记不健康: {}#{} revision={}", serviceName, instanceId, revision);
            return snapshotOf(serviceName, instance.getGroup(), revision);
        }
    }

    /** 按服务、组过滤查询实例（healthyOnly 时仅健康），返回防御性副本避免调用方改动内部状态。 */
    @Override
    public List<ServiceInstance> query(String serviceName, String group, boolean healthyOnly) {
        synchronized (lockFor(serviceName)) {
            Map<String, InstanceRecord> instances = services.get(serviceName);
            if (instances == null || instances.isEmpty()) {
                return List.of();
            }
            List<ServiceInstance> result = new ArrayList<>();
            for (InstanceRecord record : instances.values()) {
                ServiceInstance instance = record.getInstance();
                // 分组过滤：请求指定了非空 group 且与实例组不一致时跳过
                if (group != null && !group.isBlank() && !Objects.equals(group, instance.getGroup())) {
                    continue;
                }
                if (healthyOnly && !instance.isHealthy()) {
                    continue;
                }
                result.add(copyOf(instance));
            }
            return result;
        }
    }

    /**
     * 查询某服务当前版本号。
     *
     * @param serviceName 服务名
     * @return 该服务最新 revision；从未变更过（无记录）时返回 0
     */
    @Override
    public long revisionOf(String serviceName) {
        AtomicLong revision = revisions.get(serviceName);
        return revision == null ? 0L : revision.get();
    }

    /** 枚举注册表内全部实例记录（浅拷贝汇总），供健康检查器扫描超时实例。 */
    @Override
    public List<InstanceRecord> listAllRecords() {
        List<InstanceRecord> all = new ArrayList<>();
        for (Map<String, InstanceRecord> instances : services.values()) {
            all.addAll(instances.values());
        }
        return all;
    }

    /**
     * 移除过期实例（健康检查专用入口）：语义与 {@link #unregister} 相同。
     *
     * @return 移除后的快照；实例不存在（已被并发注销）时返回 null
     */
    @Override
    public RegistrySnapshot removeExpired(String serviceName, String instanceId) {
        return unregister(serviceName, instanceId);
    }

    /** 按服务名+实例 ID 查找记录（含中间 map 判空），用于心跳与内部查询 */
    private InstanceRecord find(String serviceName, String instanceId) {
        Map<String, InstanceRecord> instances = services.get(serviceName);
        if (instances == null) {
            return null;
        }
        return instances.get(instanceId);
    }

    /** 服务版本号 +1（首次变更时懒创建计数器）；revision 单调递增，客户端/推送方可据此判断快照新旧。 */
    private long bumpRevision(String serviceName) {
        return revisions.computeIfAbsent(serviceName, key -> new AtomicLong()).incrementAndGet();
    }

    /**
     * 构造某服务的当前快照：以“不过滤组、不要求健康”查询结果作为实例列表。
     * group 参数仅用于快照上的标注，不参与实例过滤。
     */
    private RegistrySnapshot snapshotOf(String serviceName, String group, long revision) {
        return RegistrySnapshot.of(serviceName, group, revision, query(serviceName, null, false));
    }

    /** 实例深拷贝（含元数据防御性拷贝），防止外部修改污染注册表 */
    private ServiceInstance copyOf(ServiceInstance source) {
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
        copy.setMetadata(source.getMetadata() == null ? new HashMap<>() : new HashMap<>(source.getMetadata()));
        return copy;
    }

    private Object lockFor(String serviceName) {
        int index = Math.floorMod(serviceName == null ? 0 : serviceName.hashCode(), LOCK_STRIPES);
        return serviceLocks[index];
    }

    private static Object[] createServiceLocks() {
        Object[] locks = new Object[LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }
}
