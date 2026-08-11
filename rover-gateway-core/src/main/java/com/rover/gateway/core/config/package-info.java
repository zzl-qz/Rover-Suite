/**
 * Author: Daylight
 * Created: 2026-08-08 14:59:00
 * Description: 定义 Gateway 运行时配置管理包
 *
 * 这个包是什么：Gateway 可热更新运行时配置的管理与应用层。
 * 核心职责：GatewayRuntimeConfigManager 注册/校验/落盘；Applier 把变更打进 GatewayRuntime。
 * 被谁用：GatewayHttpServer 启动流程；GatewayManageApi 查询/更新配置。
 */
package com.rover.gateway.core.config;
