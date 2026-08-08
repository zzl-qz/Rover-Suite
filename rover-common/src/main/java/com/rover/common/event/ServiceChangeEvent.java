/**
 * 作者：Daylight
 * 创建时间：2026-08-08 10:34:00
 * 描述：承载服务实例变更通知的数据
 */
package com.rover.common.event;

import com.rover.common.model.ServiceInstance;
import java.util.List;
import lombok.Data;

@Data
public class ServiceChangeEvent implements Event {

    private String serviceName;
    private List<ServiceInstance> instances;
}
