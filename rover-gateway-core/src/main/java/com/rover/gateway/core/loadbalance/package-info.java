/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 定义 Gateway 负载均衡策略接口与实现包
 *
 * 这个包是什么：动态发现模式下从多个实例里选一个的负载均衡层。
 * 核心职责：LoadBalancer 契约、轮询和随机两种内置实现。
 * 被谁用：RouteAndProxyFilter 选实例；GatewayRuntime 按配置热切换策略。
 */
package com.rover.gateway.core.loadbalance;
