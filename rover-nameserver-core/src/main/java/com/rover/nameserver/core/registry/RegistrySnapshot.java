package com.rover.nameserver.core.registry;

import com.rover.common.model.ServiceInstance;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:50:00
 * Description: 某次变更后的服务快照
 */
@Data
public class RegistrySnapshot {

    /** 服务名 */
    private String serviceName;
    /** 分组 */
    private String group;
    /** 版本号 */
    private long revision;
    /** 当前实例列表 */
    private List<ServiceInstance> instances = new ArrayList<>();

    public static RegistrySnapshot of(
            String serviceName, String group, long revision, List<ServiceInstance> instances) {
        RegistrySnapshot snapshot = new RegistrySnapshot();
        snapshot.setServiceName(serviceName);
        snapshot.setGroup(group);
        snapshot.setRevision(revision);
        snapshot.setInstances(instances == null ? new ArrayList<>() : new ArrayList<>(instances));
        return snapshot;
    }
}
