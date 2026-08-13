package com.rover.common.spi.discovery;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 定义服务实例暴露的基础信息契约
 *
 * 这个接口是什么：服务实例的最小区块信息定义。
 * 核心职责：抽象出「哪个服务的哪个地址可被调用」，使服务发现、缓存、
 * 负载均衡等消费方只依赖这三个基础字段即可完成寻址。
 * 被谁用：ServiceInstance 等具体实例模型实现；网关与客户端服务发现代码消费。
 */
public interface Instance {

    /** @return 所属服务名 */
    String getServiceName();

    /** @return 实例主机地址 */
    String getHost();

    /** @return 实例端口 */
    int getPort();
}
