/**
 * Author: Daylight
 * Created: 2026-08-08 17:55:00
 * Description: 通用并发小工具
 *
 * 包职责：为 Rover 各模块提供与网络请求、周期任务相关的并发基础组件。
 * 包含：请求响应挂起表 PendingRequestTable(按 ID 匹配在途请求)、
 * 固定间隔周期任务 PeriodicTask(心跳等简单定时)、
 * 请求 ID 生成器 RequestIdGenerator(线程安全的自增 ID)。
 * 使用者：rover-client、rover-registry 等依赖该模块的组件。
 */
package com.rover.common.concurrent;
