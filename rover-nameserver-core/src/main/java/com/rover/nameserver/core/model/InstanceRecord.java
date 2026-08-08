package com.rover.nameserver.core.model;

import com.rover.common.model.ServiceInstance;
import com.rover.common.protocol.RegisterRequest;
import java.util.HashMap;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:50:00
 * Description: 注册表里的实例记录
 */
@Data
public class InstanceRecord {

    private ServiceInstance instance;
    private long lastHeartbeatMillis;

    public static InstanceRecord from(RegisterRequest request) {
        long now = System.currentTimeMillis();
        ServiceInstance instance = new ServiceInstance();
        instance.setServiceName(request.getServiceName());
        instance.setHost(request.getHost());
        instance.setPort(request.getPort());
        instance.setInstanceId(request.getInstanceId());
        instance.setRegisterTime(request.getRegisterTime() > 0 ? request.getRegisterTime() : now);
        instance.setHealthy(true);
        instance.setWeight(request.getWeight() <= 0 ? 100 : request.getWeight());
        instance.setGroup(request.getGroup());
        instance.setZone(request.getZone());
        instance.setEphemeral(request.isEphemeral());
        instance.setMetadata(request.getMetadata() == null ? new HashMap<>() : new HashMap<>(request.getMetadata()));

        InstanceRecord record = new InstanceRecord();
        record.setInstance(instance);
        record.setLastHeartbeatMillis(now);
        return record;
    }

    public void touchHeartbeat() {
        this.lastHeartbeatMillis = System.currentTimeMillis();
        if (this.instance != null) {
            this.instance.setHealthy(true);
        }
    }
}
