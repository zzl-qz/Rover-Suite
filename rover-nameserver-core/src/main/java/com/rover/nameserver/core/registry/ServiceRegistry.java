package com.rover.nameserver.core.registry;

import com.rover.common.model.ServiceInstance;
import com.rover.common.protocol.RegisterRequest;
import com.rover.nameserver.core.model.InstanceRecord;
import java.util.List;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:50:00
 * Description: 服务注册表
 */
public interface ServiceRegistry {

    RegistrySnapshot register(RegisterRequest request);

    RegistrySnapshot unregister(String serviceName, String instanceId);

    boolean heartbeat(String serviceName, String instanceId);

    List<ServiceInstance> query(String serviceName, String group, boolean healthyOnly);

    long revisionOf(String serviceName);

    List<InstanceRecord> listAllRecords();

    RegistrySnapshot removeExpired(String serviceName, String instanceId);
}
