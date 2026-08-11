/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 定义 Gateway 业务前缀路由匹配能力包
 *
 * 这个包是什么：Gateway 路由规则的定义、匹配和 overlay 持久化。
 * 核心职责：RouteConfig 模型、RouteMatcher 长前缀优先匹配、RouteOverlayStore 落盘。
 * 被谁用：RouteAndProxyFilter 匹配路由；GatewayRuntime/ManageApi 热更新路由。
 */
package com.rover.gateway.core.route;
