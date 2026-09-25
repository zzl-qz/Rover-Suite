package com.rover.agent.core.snapshot;

/**
 * 一个注册实例的只读快照。
 *
 * @param serviceName 服务名
 * @param group       分组；为空表示默认组
 * @param host        实例地址
 * @param port        实例端口
 * @param healthy     注册中心标记的健康状态
 */
public record InstanceSnapshot(String serviceName, String group, String host, int port, boolean healthy) { }