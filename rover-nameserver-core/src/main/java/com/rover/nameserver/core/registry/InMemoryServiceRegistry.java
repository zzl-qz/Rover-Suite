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
 * Created: 2026-08-08 17:50:00
 * Description: 内存注册表，单机先用这个
 */
@Slf4j
public class InMemoryServiceRegistry implements ServiceRegistry {

    private final Map<String, Map<String, InstanceRecord>> services = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> revisions = new ConcurrentHashMap<>();

    @Override
    public RegistrySnapshot register(RegisterRequest request) {
        InstanceRecord record = InstanceRecord.from(request);
        String serviceName = record.getInstance().getServiceName();
        String instanceId = record.getInstance().getInstanceId();

        services.computeIfAbsent(serviceName, key -> new ConcurrentHashMap<>())
                .put(instanceId, record);
        long revision = bumpRevision(serviceName);
        log.info("实例注册成功: {}#{} revision={}", serviceName, instanceId, revision);
        return snapshotOf(serviceName, record.getInstance().getGroup(), revision);
    }

    @Override
    public RegistrySnapshot unregister(String serviceName, String instanceId) {
        Map<String, InstanceRecord> instances = services.get(serviceName);
        if (instances == null) {
            return null;
        }
        InstanceRecord removed = instances.remove(instanceId);
        if (removed == null) {
            return null;
        }
        if (instances.isEmpty()) {
            services.remove(serviceName, instances);
        }
        long revision = bumpRevision(serviceName);
        log.info("实例注销: {}#{} revision={}", serviceName, instanceId, revision);
        return snapshotOf(serviceName, removed.getInstance().getGroup(), revision);
    }

    @Override
    public boolean heartbeat(String serviceName, String instanceId) {
        InstanceRecord record = find(serviceName, instanceId);
        if (record == null) {
            return false;
        }
        record.touchHeartbeat();
        return true;
    }

    @Override
    public List<ServiceInstance> query(String serviceName, String group, boolean healthyOnly) {
        Map<String, InstanceRecord> instances = services.get(serviceName);
        if (instances == null || instances.isEmpty()) {
            return List.of();
        }
        List<ServiceInstance> result = new ArrayList<>();
        for (InstanceRecord record : instances.values()) {
            ServiceInstance instance = record.getInstance();
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

    @Override
    public long revisionOf(String serviceName) {
        AtomicLong revision = revisions.get(serviceName);
        return revision == null ? 0L : revision.get();
    }

    @Override
    public List<InstanceRecord> listAllRecords() {
        List<InstanceRecord> all = new ArrayList<>();
        for (Map<String, InstanceRecord> instances : services.values()) {
            all.addAll(instances.values());
        }
        return all;
    }

    @Override
    public RegistrySnapshot removeExpired(String serviceName, String instanceId) {
        return unregister(serviceName, instanceId);
    }

    private InstanceRecord find(String serviceName, String instanceId) {
        Map<String, InstanceRecord> instances = services.get(serviceName);
        if (instances == null) {
            return null;
        }
        return instances.get(instanceId);
    }

    private long bumpRevision(String serviceName) {
        return revisions.computeIfAbsent(serviceName, key -> new AtomicLong()).incrementAndGet();
    }

    private RegistrySnapshot snapshotOf(String serviceName, String group, long revision) {
        return RegistrySnapshot.of(serviceName, group, revision, query(serviceName, null, false));
    }

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
}
