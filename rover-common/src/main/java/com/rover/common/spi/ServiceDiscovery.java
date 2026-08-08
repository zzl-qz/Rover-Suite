package com.rover.common.spi;

import java.util.List;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 定义按服务名发现可用实例的能力
 */
public interface ServiceDiscovery {

    List<Instance> getInstances(String serviceName);
}
