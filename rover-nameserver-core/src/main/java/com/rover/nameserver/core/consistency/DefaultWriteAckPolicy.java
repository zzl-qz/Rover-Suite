package com.rover.nameserver.core.consistency;

import com.rover.common.protocol.AckMode;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:40:00
 * Description: 默认写确认策略
 *
 * 这个类是什么：WriteAckPolicy 的默认实现，对应单机/简单部署场景。
 * 核心职责：①resolve 以服务端默认为准，允许覆盖且客户端显式指定时才用客户端模式；
 * ②requiredAcks 单机恒为 1，集群下 IMMEDIATE=1、MAJORITY=过半、ALL=全副本。
 * 被谁用：NameserverTcpServer 默认装配并传给请求分发器；后续集群实现可替换本类。
 */
public class DefaultWriteAckPolicy implements WriteAckPolicy {

    /**
     * 协商 ACK 模式：默认以服务端配置为准，覆盖需同时满足
     * 「允许客户端覆盖」且「客户端显式指定」两个条件。
     *
     * @return 最终 ACK 模式；serverDefault 为 null 时回退为 {@link AckMode#IMMEDIATE}（非 null）
     */
    @Override
    public AckMode resolve(AckMode serverDefault, AckMode requestMode, boolean allowClientOverride) {
        AckMode fallback = serverDefault == null ? AckMode.IMMEDIATE : serverDefault;
        // 不允许覆盖或客户端未指定：一律用服务端默认
        if (!allowClientOverride || requestMode == null) {
            return fallback;
        }
        return requestMode;
    }

    /**
     * 计算需要确认的数量。
     *
     * @param mode            ACK 模式；null 按 IMMEDIATE 处理
     * @param replicationFactor 副本数（取 max(值,1) 防止非法配置）
     * @param clusterEnabled  集群开关
     * @return 需要的确认数：单机恒为 1；集群下依模式为 1 / 过半 / 全副本
     */
    @Override
    public int requiredAcks(AckMode mode, int replicationFactor, boolean clusterEnabled) {
        int factor = Math.max(replicationFactor, 1);
        // 单机没什么好等的，1 就够了
        if (!clusterEnabled || factor <= 1) {
            return 1;
        }
        AckMode effective = mode == null ? AckMode.IMMEDIATE : mode;
        return switch (effective) {
            case IMMEDIATE -> 1;
            case MAJORITY -> (factor / 2) + 1;
            case ALL -> factor;
        };
    }
}