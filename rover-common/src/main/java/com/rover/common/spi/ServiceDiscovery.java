/**
 * 作者：Daylight
 * 创建时间：2026-08-08 10:34:00
 * 描述：定义按服务名发现可用实例的能力
 */
package com.rover.common.spi;

import java.util.List;

public interface ServiceDiscovery {

    List<Instance> getInstances(String serviceName);
}
