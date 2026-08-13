/**
 * Author: Daylight
 * Created: 2026-08-13
 * Description: 上游负载均衡（静态多 IP / 动态发现共用）
 *
 * 契约在 com.rover.common.spi.loadbalance.LoadBalancer；本包是内置算法与 Factory。
 * 自定义：plugins 里 SPI，或配置策略为类全名。
 */
package com.rover.gateway.core.loadbalance;
