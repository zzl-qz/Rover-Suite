package com.rover.common.protocol;

import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: 注销请求
 *
 * 这个类是什么：客户端主动下线某实例的请求体。
 * 核心职责：按服务名 + 实例 ID 删除注册信息，reason 记录下线原因便于审计排查。
 * 被谁用：客户端优雅停机流程发送；注册中心服务端 UNREGISTER_REQUEST 处理逻辑解析。
 */
@Data
public class UnregisterRequest {

    /** 服务名 */
    private String serviceName;
    /** 实例 ID */
    private String instanceId;
    /** 注销原因，可选 */
    private String reason;
}
