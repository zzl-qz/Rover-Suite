/**
 * Author: Daylight
 * Created: 2026-08-08 14:22:00
 * Description: 定义 Gateway 对外 HTTP 服务启动与请求处理包
 *
 * 这个包是什么：Gateway 的 Netty HTTP 服务端入口层。
 * 核心职责：GatewayHttpServer 启动监听；GatewayHttpServerHandler 分发管理口和业务链。
 * 被谁用：rover-gateway 启动模块创建 GatewayHttpServer 并 start/shutdown。
 */
package com.rover.gateway.core.server;
