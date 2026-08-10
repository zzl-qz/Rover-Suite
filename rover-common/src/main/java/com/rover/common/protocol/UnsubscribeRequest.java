package com.rover.common.protocol;

import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:40:00
 * Description: 取消订阅
 *
 * 这个类是什么：客户端停止接收某服务变更推送的请求体。
 * 核心职责：按服务名(可含分组)解除服务端侧订阅关系，配合连接关闭做清理。
 * 被谁用：客户端取消订阅流程发送；注册中心服务端 UNSUBSCRIBE_REQUEST 处理逻辑解析。
 */
@Data
public class UnsubscribeRequest {

    /** 服务名 */
    private String serviceName;
    /** 分组，空表示全部解除 */
    private String group;
}
