package com.rover.common.protocol;

import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: 注销请求
 */
@Data
public class UnregisterRequest {

    private String serviceName;
    private String instanceId;

    // 可选，方便排障
    private String reason;
}
