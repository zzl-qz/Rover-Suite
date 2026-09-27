package com.rover.agent.core.snapshot;

/**
 * 一个注册实例的只读快照。
 *
 * <p>字段取舍服务于「能不能安全地指向一个实例」：{@code instanceId} 是注册中心内稳定的实例标识，
 * 没有它就只能在证据里看，无法对具体实例下判断；{@code lastHeartbeatMillis} 用来区分
 * 「实例不健康」与「实例已经不在了」——两者对后续处置的含义完全不同。
 *
 * @param serviceName         服务名
 * @param group               分组；为空表示默认组
 * @param instanceId          注册中心内稳定实例标识；为空表示来源未提供
 * @param host                实例地址
 * @param port                实例端口
 * @param healthy             注册中心标记的健康状态
 * @param weight              负载权重
 * @param ephemeral           是否临时实例（心跳超时可被剔除）
 * @param lastHeartbeatMillis 最后一次心跳时间（毫秒）；0 表示来源未提供
 */
public record InstanceSnapshot(
        String serviceName,
        String group,
        String instanceId,
        String host,
        int port,
        boolean healthy,
        int weight,
        boolean ephemeral,
        long lastHeartbeatMillis) { }
