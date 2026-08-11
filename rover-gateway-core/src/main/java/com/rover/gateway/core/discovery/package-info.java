/**
 * Author: Daylight
 * Created: 2026-08-10 16:20:00
 * Description: 定义 Gateway 上游服务发现能力包
 *
 * 这个包是什么：Gateway 查后端实例的发现层，支持静态和 Nameserver 动态两种模式。
 * 核心职责：ServiceDiscovery 契约、Nameserver 订阅对账、发现配置模型。
 * 被谁用：GatewayHttpServer 启动时创建；RouteAndProxyFilter 动态选上游。
 */
package com.rover.gateway.core.discovery;
