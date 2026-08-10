package com.rover.common.protocol;

import java.util.HashMap;
import java.util.Map;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: 注册请求
 *
 * 这个类是什么：客户端首次上报实例信息给注册中心的请求体。
 * 核心职责：完整描述要注册的实例(地址、权重、分组、可用区、扩展 KV 等)，
 * 服务端据此登记实例并开始接受心跳维持其租约。
 * 被谁用：客户端启动注册流程发送；注册中心服务端 REGISTER_REQUEST 处理逻辑解析。
 */
@Data
public class RegisterRequest {

    /** 服务名 */
    private String serviceName;
    /** 实例地址 */
    private String host;
    /** 实例端口 */
    private int port;
    /** 实例 ID */
    private String instanceId;
    /** 注册时间；0 表示服务端填写 */
    private long registerTime;
    /** 权重 */
    private int weight = 100;
    /** 分组 */
    private String group;
    /** 机房/可用区 */
    private String zone;
    /** 临时实例，默认 true */
    private boolean ephemeral = true;
    /** 扩展 KV */
    private Map<String, String> metadata = new HashMap<>();
}
