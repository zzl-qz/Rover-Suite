package com.rover.common.event;

import com.rover.common.model.ServiceInstance;

import java.util.List;

public class ServiceChangeEvent implements Event {

    private String serviceName;
    private List<ServiceInstance> instances;

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public List<ServiceInstance> getInstances() {
        return instances;
    }

    public void setInstances(List<ServiceInstance> instances) {
        this.instances = instances;
    }
}
