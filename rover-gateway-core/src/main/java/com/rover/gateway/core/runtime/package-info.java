/**
 * Author: Daylight
 * Created: 2026-08-10 16:40:00
 * Description: 定义 Gateway 运行时可变状态包
 *
 * 这个包是什么：Gateway 进程内热更新所需的运行时状态容器。
 * 核心职责：GatewayRuntime 集中持有路由、过滤器链、代理客户端、LB 等可变组件。
 * 被谁用：GatewayHttpServer、GatewayManageApi、GatewayRuntimeConfigApplier。
 */
package com.rover.gateway.core.runtime;
