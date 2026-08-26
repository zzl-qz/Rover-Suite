package com.rover.common.spi.discovery;

import java.util.Map;

/** 可选的服务发现运行状态接口，供管理面和指标系统读取。 */
public interface ServiceDiscoveryStatus {

    /** 返回当前连接、订阅失败和快照更新时间等状态。 */
    Map<String, Object> status();
}
