/**
 * Author: Daylight
 * Created: 2026-08-08 14:59:00
 * Description: 定义 Nameserver 运行时配置管理包
 *
 * 本包负责 Nameserver 可热更新的运行时配置：
 * {@link com.rover.nameserver.core.config.NameserverRuntimeConfigManager} 维护配置项注册表（含默认值与中文描述），
 * 支持从 overlay 文件（config/nameserver-runtime.overlay.json）恢复已持久化的配置，
 * 并通过 {@link com.rover.nameserver.core.config.NameserverRuntimeConfigApplier} 把每次变更实时应用到
 * HealthChecker / PushService 等运行时组件，实现免重启生效。
 */
package com.rover.nameserver.core.config;