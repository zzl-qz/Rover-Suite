package com.rover.nameserver.core.consistency;

import com.rover.common.protocol.AckMode;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:40:00
 * Description: 写确认策略，后面集群可以换实现
 *
 * 核心职责：定义「写请求需要多少个确认才算成功」的决策抽象，包含两层能力：</p>
 * <ol>
 *     <li>{@link #resolve}：在服务端默认、客户端请求、是否允许客户端覆盖之间
 *     协商出本次写请求实际生效的 ACK 模式；</li>
 *     <li>{@link #requiredAcks}：把 ACK 模式换算成具体需要等待的确认数量。</li>
 * </ol>
 *
 * 被 {@link com.rover.nameserver.core.server.NameserverRequestDispatcher} 在
 * 注册/注销时调用，结果随响应返回给客户端。当前单机部署由
 * {@link DefaultWriteAckPolicy} 实现（单机一律 1 个确认）；后续做集群时
 * 可替换为基于副本同步的实现，上层分发逻辑无需改动。</p>
 */
public interface WriteAckPolicy {

    /**
     * 协商本次写请求最终使用的 ACK 模式。
     *
     * @param serverDefault      服务端配置的默认 ACK 模式；null 时视为 {@link AckMode#IMMEDIATE}
     * @param requestMode        客户端请求中携带的 ACK 模式；可能为 null（未指定）
     * @param allowClientOverride 是否允许客户端覆盖服务端默认值
     * @return 最终生效的 ACK 模式（非 null）
     */
    AckMode resolve(AckMode serverDefault, AckMode requestMode, boolean allowClientOverride);

    /**
     * 根据 ACK 模式、副本数与集群开关，计算需要多少个节点确认才算写成功。
     *
     * @param mode            已协商的 ACK 模式（可复用 {@link #resolve} 的结果）
     * @param replicationFactor 配置的副本数（<=0 时按 1 处理）
     * @param clusterEnabled  集群是否开启；单机模式下无论何种模式都只需 1 个确认
     * @return 需要的确认数量，取值在 [1, max(replicationFactor, 1)] 区间内
     */
    int requiredAcks(AckMode mode, int replicationFactor, boolean clusterEnabled);
}