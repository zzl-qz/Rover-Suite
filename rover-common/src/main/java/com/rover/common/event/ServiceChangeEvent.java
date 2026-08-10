package com.rover.common.event;

import com.rover.common.model.ServiceInstance;
import java.util.List;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 承载服务实例变更通知的数据
 *
 * 这个类是什么：服务实例列表变更事件，实现 Event 接口。
 * 核心职责：注册中心在任何时刻(注册/注销/健康状态变化)发来全量实例快照时，
 * 用该事件把「服务名 + 最新实例列表」推给网关等订阅方，驱动本地缓存刷新。
 * 被谁用：注册中心客户端发布，网关/负载均衡缓存订阅消费。
 */
@Data
public class ServiceChangeEvent implements Event {

    /** 发生变更的服务名 */
    private String serviceName;
    /** 变更后的全量实例列表 */
    private List<ServiceInstance> instances;
}
