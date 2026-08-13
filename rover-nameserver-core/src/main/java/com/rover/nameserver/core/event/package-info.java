/**
 * Nameserver 进程内事件：aero-mq 同构。
 *
 * model = 协议事件；spi/listener = 业务；support = 依赖门面与回包工具。
 * EventBusBootstrap 显式 register 各 Listener；Dispatcher 只做 TCP→Event。
 */
package com.rover.nameserver.core.event;
