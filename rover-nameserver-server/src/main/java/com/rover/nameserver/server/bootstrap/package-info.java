/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 定义 nameserver 服务端启动包
 *
 * 本包是 Rover Nameserver 可执行服务端的入口：{@link NameserverApplication}
 * 的 main 方法加载 YAML 配置、组装核心模块（rover-nameserver-core）的
 * NameserverTcpServer 并启动，同时注册 JVM 关闭钩子保证优雅退出。</p>
 */
package com.rover.nameserver.server.bootstrap;