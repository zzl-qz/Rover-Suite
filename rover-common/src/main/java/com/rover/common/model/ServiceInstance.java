/**
 * 作者：Daylight
 * 创建时间：2026-08-08 10:34:00
 * 描述：描述服务名、地址、端口和健康状态等实例信息
 */
package com.rover.common.model;

import com.rover.common.spi.Instance;
import lombok.Data;

@Data
public class ServiceInstance implements Instance {

    private String serviceName;
    private String host;
    private int port;
    private String instanceId;
    private long registerTime;
    private boolean healthy;
}
