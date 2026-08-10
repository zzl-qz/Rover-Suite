/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 定义 nameserver 客户端消息处理包
 *
 * 包职责：Netty 流水线最内层的业务收包处理（NameserverClientHandler）——
 * 分派服务端推送与 RPC 响应、按 requestId 完成在途请求、连接断开时快速失败在途
 * 请求并触发客户端的重连流程。
 */
package com.rover.nameserver.client.handler;
