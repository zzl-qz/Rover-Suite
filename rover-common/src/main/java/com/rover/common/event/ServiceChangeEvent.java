package com.rover.common.event;

import com.rover.common.model.ServiceInstance;
import java.util.List;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 服务实例变更通知事件
 */
@Data
public class ServiceChangeEvent implements Event {

    /** 发生变更的服务名 */
    private String serviceName;
    /** 变更后的全量实例列表 */
    private List<ServiceInstance> instances;
}
