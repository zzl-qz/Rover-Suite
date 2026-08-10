package com.rover.common.protocol;

import java.util.HashMap;
import java.util.Map;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: 心跳请求
 *
 * 这个类是什么：客户端按周期发给注册中心的保活消息体。
 * 核心职责：携带服务名与实例 ID 上报「我还活着」，服务端据此续期实例租约
 * (临时实例心跳超时会被剔除)。
 * 被谁用：客户端心跳定时任务发送；注册中心服务端心跳处理逻辑解析。
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
