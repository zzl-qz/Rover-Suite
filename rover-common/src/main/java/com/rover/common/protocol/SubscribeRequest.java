package com.rover.common.protocol;

import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: 订阅服务变更
 *
 * 这个类是什么：客户端发起实例变更订阅的请求体。
 * 核心职责：声明要订阅的服务(可限定分组)，并上报 knownRevision 让服务端判断
 * 是否需要立即补发差异/全量快照。
 * 被谁用：客户端缓存订阅流程发送；注册中心服务端 SUBSCRIBE_REQUEST 处理逻辑解析。
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
