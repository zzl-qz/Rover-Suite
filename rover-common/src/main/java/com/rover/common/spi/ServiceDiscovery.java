package com.rover.common.spi;

import java.util.List;

public interface ServiceDiscovery {

    List<Instance> getInstances(String serviceName);
}
