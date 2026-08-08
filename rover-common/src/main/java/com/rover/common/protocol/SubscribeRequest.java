package com.rover.common.protocol;

import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: 订阅服务变更
 */
@Data
public class SubscribeRequest {

    private String serviceName;
    private String group;

    // 客户端当前看到的版本，后面做增量也许用得上
    private long knownRevision;
}
