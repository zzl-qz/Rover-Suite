/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 定义 Gateway 真实 HTTP 反向代理能力包
 *
 * 这个包是什么：Gateway 把客户端请求转发到后端的 HTTP 代理层。
 * 核心职责：HttpProxyClient 基于 java.net.http 同步转发，处理 Header 和错误响应。
 * 被谁用：RouteAndProxyFilter 终端转发；GatewayRuntime 持有单例。
 */
package com.rover.gateway.core.proxy;
