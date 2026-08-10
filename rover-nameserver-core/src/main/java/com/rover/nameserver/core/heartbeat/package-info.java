/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 定义 nameserver 心跳处理包
 *
 * 当前心跳逻辑直接由注册表（{@code heartbeat()} 刷新 LastHeartbeatMillis）
 * 与 {@link com.rover.nameserver.core.health.HealthChecker}（超时判定与剔除）
 * 承担，本包预留为独立的心跳收包/状态处理位置，为后续扩展
 * （如心跳统计、多协议心跳）留出空间。</p>
 */
package com.rover.nameserver.core.heartbeat;