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

    /** 服务名 */
    private String serviceName;
    /** 实例地址 */
    private String host;
    /** 实例端口 */
    private int port;
    /** 实例唯一 ID */
    private String instanceId;
    /** 注册时间（毫秒） */
    private long registerTime;
    /** 是否健康 */
    private boolean healthy = true;
    /** 负载权重 */
    private int weight = 100;
    /** 逻辑分组 */
    private String group;
    /** 机房/可用区，预留 */
    private String zone;
    /** 临时实例：心跳超时可剔除 */
    private boolean ephemeral = true;
    /** 扩展信息 */
    private Map<String, String> metadata = new HashMap<>();
}
