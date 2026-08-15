package com.rover.common.protocol;

import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-07 10:10:00
 * Description: 注销请求：按服务名 + 实例 ID 下线实例，reason 记录下线原因便于审计
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
