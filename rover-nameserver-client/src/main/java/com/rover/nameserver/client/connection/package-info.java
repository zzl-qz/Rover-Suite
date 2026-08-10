/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 定义 nameserver 客户端连接管理包
 *
 * 包职责：客户端连接与生命周期管理——NameserverClient 是面向业务的主入口
 * （注册/注销/心跳/查询/订阅、请求响应配对、重连恢复、心跳续约），
 * NameserverClientOptions 承载连接与任务相关的全部配置。
 */
package com.rover.nameserver.client.connection;
