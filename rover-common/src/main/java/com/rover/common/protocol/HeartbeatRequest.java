package com.rover.common.protocol;

import java.util.HashMap;
import java.util.Map;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: 心跳请求
 */
@Data
public class HeartbeatRequest {

    /** 服务名 */
    private String serviceName;
    /** 实例 ID */
    private String instanceId;
    /** 客户端时间，仅排查用 */
    private long clientTimeMillis;
    /** 扩展信息，预留 */
    private Map<String, String> metadata = new HashMap<>();
}
