package com.rover.common.protocol;

import java.util.HashMap;
import java.util.Map;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-07 10:00:00
 * Description: 心跳请求：客户端周期上报保活，服务端据此续期实例租约
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
    /** 集群鉴权 token，服务端开启鉴权时校验；空表示不鉴权 */
    private String token;
}
