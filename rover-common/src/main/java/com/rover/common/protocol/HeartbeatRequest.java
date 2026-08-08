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

    private String serviceName;
    private String instanceId;
    private long clientTimeMillis;

    // 现在基本不用，后面有需要再填
    private Map<String, String> metadata = new HashMap<>();
}
