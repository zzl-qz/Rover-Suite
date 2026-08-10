/**
 * Author: Daylight
 * Created: 2026-08-08 17:40:00
 * Description: 写确认相关
 *
 * 定义写确认（Write-Ack）策略抽象：客户端/服务端请求的 ACK 强度如何协商，
 * 以及按 ACK 模式与副本数计算需要等待几个确认。当前为单机部署，
 * {@link DefaultWriteAckPolicy} 是默认实现（单机一律 1 个确认即可），
 * 后续做集群时可在不修改上层调用方的前提下替换实现。</p>
 */
package com.rover.nameserver.core.consistency;