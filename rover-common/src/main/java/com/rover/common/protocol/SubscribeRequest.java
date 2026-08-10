package com.rover.common.protocol;

import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: 订阅服务变更
 */
@Data
public class SubscribeRequest {

    /** 服务名 */
    private String serviceName;
    /** 分组，空表示全收 */
    private String group;
    /** 客户端已知版本 */
    private long knownRevision;
}
