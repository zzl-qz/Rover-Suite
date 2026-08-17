package com.rover.nameserver.core.registry;

import com.rover.common.model.ServiceInstance;
import com.rover.common.protocol.RegisterRequest;
import com.rover.nameserver.core.model.InstanceRecord;
import com.rover.nameserver.core.registration.RegistrationOwner;
import com.rover.nameserver.core.registration.RegistrationResult;
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

    /**
     * 注册或续租实例：同 owner、同数据只刷新心跳；数据或 owner 变化时原子替换并 bump revision。
     */
    @Override
    public RegistrationResult register(RegisterRequest request, RegistrationOwner owner) {
        InstanceRecord candidate = InstanceRecord.from(request, owner);
        String serviceName = candidate.getInstance().getServiceName();
        String instanceId = candidate.getInstance().getInstanceId();

        synchronized (lockFor(serviceName)) {
            InstanceRecord current = find(serviceName, instanceId);
            if (current != null
                    && current.isOwnedBy(owner)
                    && current.hasSameRegistration(request)) {
                boolean recovered = current.touchHeartbeat();
                if (!recovered) {
                    return RegistrationResult.unchanged(revisionOf(serviceName));
                }
                long revision = bumpRevision(serviceName);
                log.info("实例重新注册恢复健康: {}#{} revision={}", serviceName, instanceId, revision);
                return RegistrationResult.changed(
                        snapshotOf(serviceName, current.getInstance().getGroup(), revision));
            }

            // 和外层注销走同一把 compute，避免「空了就摘」把刚 put 进去的新实例一起摘掉
            services.compute(serviceName, (key, instances) -> {
                Map<String, InstanceRecord> map =
                        instances == null ? new ConcurrentHashMap<>() : instances;
                map.put(instanceId, candidate);
                return map;
            });
            long revision = bumpRevision(serviceName);
            log.info("实例注册成功: {}#{} revision={}", serviceName, instanceId, revision);
            return RegistrationResult.changed(
                    snapshotOf(serviceName, candidate.getInstance().getGroup(), revision));
        }
    }

    /** 仅当前 owner 可以注销；旧连接/session 不得误删已被新 owner 接管的实例。 */
    @Override
    public RegistrationResult unregister(
            String serviceName, String instanceId, RegistrationOwner owner) {
        synchronized (lockFor(serviceName)) {
            InstanceRecord current = find(serviceName, instanceId);
            long currentRevision = revisionOf(serviceName);
            if (current == null) {
                return RegistrationResult.notFound(currentRevision);
            }
            if (!current.isOwnedBy(owner)) {
                return RegistrationResult.ownerMismatch(currentRevision);
            }
            removeRecord(serviceName, instanceId);
            long revision = bumpRevision(serviceName);
            log.info("实例注销: {}#{} revision={}", serviceName, instanceId, revision);
            return RegistrationResult.changed(
                    snapshotOf(serviceName, current.getInstance().getGroup(), revision));
        }
    }

    /**
     * 处理一次心跳：刷新最近心跳时间；若从不健康恢复则 bump revision。
     */
    @Override
    public RegistrationResult heartbeat(
            String serviceName, String instanceId, RegistrationOwner owner) {
        synchronized (lockFor(serviceName)) {
            InstanceRecord record = find(serviceName, instanceId);
            long currentRevision = revisionOf(serviceName);
            if (record == null) {
                return RegistrationResult.notFound(currentRevision);
            }
            if (!record.isOwnedBy(owner)) {
                return RegistrationResult.ownerMismatch(currentRevision);
            }
            boolean recovered = record.touchHeartbeat();
            if (!recovered) {
                return RegistrationResult.unchanged(currentRevision);
            }
            long revision = bumpRevision(serviceName);
            log.info("实例心跳恢复健康: {}#{} revision={}", serviceName, instanceId, revision);
            return RegistrationResult.changed(
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

    /** 健康检查专用条件删除：owner 和超时条件都必须仍成立。 */
    @Override
    public RegistrySnapshot removeExpired(
            String serviceName,
            String instanceId,
            RegistrationOwner expectedOwner,
            long heartbeatDeadlineMillis) {
        synchronized (lockFor(serviceName)) {
            InstanceRecord current = find(serviceName, instanceId);
            if (current == null
                    || !current.isOwnedBy(expectedOwner)
                    || !current.isHeartbeatExpired(heartbeatDeadlineMillis)) {
                return null;
            }
            removeRecord(serviceName, instanceId);
            long revision = bumpRevision(serviceName);
            log.info("过期实例剔除: {}#{} revision={}", serviceName, instanceId, revision);
            return snapshotOf(serviceName, current.getInstance().getGroup(), revision);
        }
    }

    /** 按服务名+实例 ID 查找记录（含中间 map 判空），用于心跳与内部查询 */
    private InstanceRecord find(String serviceName, String instanceId) {
        Map<String, InstanceRecord> instances = services.get(serviceName);
        if (instances == null) {
            return null;
        }
        return instances.get(instanceId);
    }

    /** 调用方已持有 service stripe lock；删除末实例时同时清理外层服务键。 */
    private void removeRecord(String serviceName, String instanceId) {
        services.computeIfPresent(serviceName, (key, current) -> {
            current.remove(instanceId);
            return current.isEmpty() ? null : current;
        });
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
