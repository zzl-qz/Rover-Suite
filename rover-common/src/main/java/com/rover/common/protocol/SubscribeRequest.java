package com.rover.common.protocol;

import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-07 09:40:00
 * Description: 订阅服务变更请求：上报 knownRevision 让服务端判断是否补发差异/全量快照
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
