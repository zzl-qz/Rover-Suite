/**
 * Author: Daylight
 * Created: 2026-08-08 17:50:00
 * Description: Nameserver TCP 服务端
 *
 * 服务端的网络接入层：{@link NameserverTcpServer} 组装 Netty 服务端
 * （解码/编码/业务 handler 的 pipeline 与线程模型），
 * {@link NameserverServerHandler} 承接连接生命周期与消息进入点，
 * {@link NameserverRequestDispatcher} 按消息类型分发到注册表/订阅/推送等业务组件，
 * {@link NameserverServerOptions} 是服务端全部运行参数的不可变载体。</p>
 */
package com.rover.nameserver.core.server;