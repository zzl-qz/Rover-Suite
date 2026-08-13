package com.rover.common.model;

import java.util.HashMap;
import java.util.Map;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 服务实例信息
 *
 * 这个类是什么：服务注册中心与客户端之间流转的服务实例数据结构。
 * 核心职责：完整描述一个可调用实例的地址、生命周期属性(临时/健康)、
 * 负载属性(权重)与扩展信息(分组、可用区、metadata)。
 * 被谁用：注册中心服务端存储与推送、客户端发现缓存、网关负载均衡。
 */
@Data
public class ServiceInstance {

    /** 服务名 */
    private String serviceName;
    /** 实例地址 */
    private String host;
    /** 实例端口 */
    private int port;
    /** 实例唯一 ID */
    private String instanceId;
    /** 注册时间（毫秒） */
    private long registerTime;
    /** 是否健康 */
    private boolean healthy = true;
    /** 负载权重 */
    private int weight = 100;
    /** 逻辑分组 */
    private String group;
    /** 机房/可用区，预留 */
    private String zone;
    /** 临时实例：心跳超时可剔除 */
    private boolean ephemeral = true;
    /** 扩展信息 */
    private Map<String, String> metadata = new HashMap<>();
}
