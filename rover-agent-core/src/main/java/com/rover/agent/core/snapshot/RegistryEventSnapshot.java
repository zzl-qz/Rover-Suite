package com.rover.agent.core.snapshot;

/**
 * 一条注册中心事件快照（注册、注销、剔除、标记不健康、订阅推送）。
 *
 * 事件缓冲区按条数滚动，不随判定窗口滚动：更早的记录仍然可见，但不能用于判断当前状态，
 * 因此判定与表述都必须显式区分「窗口内」与「窗口外」。
 *
 * @param timestampMillis 事件发生时刻
 * @param type            事件类型
 * @param serviceName     涉及的服务名
 * @param instanceId      涉及的实例 ID
 * @param detail          事件说明
 */
public record RegistryEventSnapshot(long timestampMillis, String type, String serviceName,
                                    String instanceId, String detail) { }
