package com.rover.common.protocol;

import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: 查询实例
 *
 * 这个类是什么：客户端向注册中心查询实例列表的请求体。
 * 核心职责：按服务名(可附加分组与健康过滤)查询当前可用实例，
 * 用于存量同步与订阅前的一次性拉取。
 * 被谁用：客户端服务发现代码发送；注册中心服务端 QUERY_REQUEST 处理逻辑解析。
 */
@Data
public class QueryRequest {

    /** 服务名 */
    private String serviceName;
    /** 分组过滤，可空 */
    private String group;
    /** 是否只返回健康实例 */
    private boolean healthyOnly = true;
}
