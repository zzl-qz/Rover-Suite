package com.rover.common.event;

import com.rover.common.model.ServiceInstance;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 可选领域事件（服务快照变更）；Nameserver 主链路不依赖它做推送
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class ServiceChangeEvent extends Event {

    private String serviceName;
    private String group;
    private long revision;
    private ServiceChangeType changeType = ServiceChangeType.UNKNOWN;
    private List<ServiceInstance> instances = new ArrayList<>();

    public static ServiceChangeEvent of(
            String serviceName,
            String group,
            long revision,
            ServiceChangeType changeType,
            List<ServiceInstance> instances) {
        ServiceChangeEvent event = new ServiceChangeEvent();
        event.setServiceName(serviceName);
        event.setGroup(group);
        event.setRevision(revision);
        event.setChangeType(changeType == null ? ServiceChangeType.UNKNOWN : changeType);
        event.setInstances(instances == null ? new ArrayList<>() : new ArrayList<>(instances));
        return event;
    }
}
