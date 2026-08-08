package com.rover.common.model;

import com.rover.common.spi.Instance;
import java.util.HashMap;
import java.util.Map;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 服务实例信息
 */
@Data
public class ServiceInstance implements Instance {

    private String serviceName;
    private String host;
    private int port;
    private String instanceId;
    private long registerTime;
    private boolean healthy = true;
    private int weight = 100;
    private String group;
    private String zone;
    private boolean ephemeral = true;
    private Map<String, String> metadata = new HashMap<>();
}
