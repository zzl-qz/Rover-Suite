/**
 * Author: Daylight
 * Created: 2026-08-10 16:40:00
 * Description: 定义 Gateway 同口管理 API 包
 *
 * 这个包是什么：Gateway 内置 /_manage/** HTTP 管理接口层。
 * 核心职责：GatewayManageApi 提供 status、routes、configs 等 REST 端点。
 * 被谁用：GatewayHttpServerHandler 在管理路径上短路调用。
 */
package com.rover.gateway.core.manage;
