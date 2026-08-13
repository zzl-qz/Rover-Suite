package com.rover.common.event;

import com.rover.common.model.ServiceInstance;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 服务实例变更通知事件（带版本号的全量快照）
 */
@Data
public class ServiceChangeEvent implements Event {

    /** 发生变更的服务名 */
    private String serviceName;

    /** 分组；可为 null */
    private String group;

    /** 变更后服务版本号，与注册表 revision 对齐 */
    private long revision;

    /** 变更类型 */
    private ServiceChangeType changeType = ServiceChangeType.UNKNOWN;

    /** 变更后的全量实例列表 */
    private List<ServiceInstance> instances = new ArrayList<>();

    /**
     * 从常用字段快速组装事件（实例列表会拷贝一份）。
     */
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
