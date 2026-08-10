/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 定义 nameserver 健康检查包
 *
 * 提供 {@link HealthChecker}：以固定间隔扫描注册表，对心跳超时的实例
 * 标记为不健康、对过期未心跳的临时实例直接剔除，并触发变更推送。
 * 检查间隔、心跳超时、临时实例过期时间均支持运行时热更新。</p>
 */
package com.rover.nameserver.core.health;