/**
 * Author: Daylight
 * Created: 2026-08-10 16:20:00
 * Description: 定义 Gateway 上游服务发现能力包
 *
 * 这个包是什么：Gateway 侧发现实现（Nameserver / 静态空实现）与发现配置。
 * 核心职责：实现 common.spi.ServiceDiscovery；订阅对账；DiscoverySettings。
 * 契约本身在 rover-common，本包只放实现，方便 Nacos 适配模块依赖同一接口。
 */
package com.rover.gateway.core.discovery;
