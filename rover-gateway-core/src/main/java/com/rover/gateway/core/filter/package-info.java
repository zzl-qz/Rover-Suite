/**
 * Author: Daylight
 * Created: 2026-08-08 16:53:00
 * Description: 定义 Gateway 过滤器链、内置过滤器，以及 plugins 外挂加载能力
 *
 * 这个包是什么：Gateway 请求链路的 Filter 层，基于 rover-common.spi.Filter 扩展。
 * 核心职责：请求上下文、过滤器链推进、访问日志、插件加载、路由转发终端 Filter。
 * 被谁用：GatewayRuntime 组装链；GatewayHttpServerHandler 执行链。
 */
package com.rover.gateway.core.filter;
