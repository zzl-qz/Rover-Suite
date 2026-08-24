# 故障排查

这份文档用于快速定位 Rover-Suite 的常见运行问题。建议先按现象查表，再打开对应组件日志。

## 1. 常见现象速查

| 现象 | 优先检查 |
| --- | --- |
| Gateway 找不到实例 | Nameserver TCP 地址、协议 token、服务名和心跳间隔 |
| Admin 显示组件离线 | Gateway/Nameserver 管理地址、管理 token、管理端口 ACL |
| 配置保存失败 | 字段类型、枚举值、热更新标记和组件日志 |
| 路由返回 404 | `businessPrefix`、`stripPrefix`、发现模式和实例健康状态 |
| 路由返回 502 | 上游地址是否可达、连接超时、服务是否已注册 |
| 路由返回 503 | 服务无健康实例、Gateway 资源不足或限流/插件拒绝 |
| HTTP 注册返回 401 | `Authorization: Bearer` 是否等于 Nameserver 协议 token |
| 管理接口返回 401 | `X-Rover-Admin-Token` 是否等于目标组件管理 token |
| 实例频繁下线 | 心跳超时、网络抖动、实例注册的 host/port 是否可达 |
| 日志量增长过快 | 访问日志是否为 DEBUG，或是否开启 `filters.accessLog` |

## 2. 先确认组件是否在线

Gateway 和 Nameserver 都提供管理健康检查。管理 token 非空时，需要带上 `X-Rover-Admin-Token`。

```bash
curl -H "X-Rover-Admin-Token: <gateway-admin-token>" \
  http://127.0.0.1:8080/_manage/health

curl -H "X-Rover-Admin-Token: <nameserver-admin-token>" \
  http://127.0.0.1:8889/_manage/health
```

如果健康检查不通，优先确认监听地址、端口映射、防火墙和 token。

## 3. Gateway 找不到实例

这种问题通常出现在服务未注册、服务名不一致或 Gateway 订阅未完成时。

建议按顺序检查：

1. Nameserver 是否已启动，并监听 Gateway 配置中的地址。
2. 业务服务的 `serviceName` 是否与路由中的 `serviceName` 一致。
3. Java Starter 或 HTTP Registrar 是否已经注册成功。
4. 协议 token 是否一致。
5. 实例注册的 `host:port` 是否能从 Gateway 进程访问。

如果 Gateway 先于 Nameserver 启动，当前版本会依赖后续对账补齐发现。默认情况下最长可能等待约 30 秒；本地调试时也可以重启 Gateway。

## 4. 路由返回 404

404 多数来自路由匹配或路径改写。重点看这几个字段：

| 字段 | 检查点 |
| --- | --- |
| `businessPrefix` | 请求路径是否以该前缀开头 |
| `stripPrefix` | 转发给上游前是否正确裁剪路径 |
| `serviceName` | 是否能在 Nameserver 中找到同名服务 |
| `targetUrl/targetUrls` | 静态上游地址是否可访问 |

如果使用 Admin 修改过路由，`config/routes.overlay.json` 会整体覆盖 YAML 路由。YAML 修改不生效时，优先检查这个 overlay 文件。

## 5. 路由返回 502 或 503

502 更常见于上游连接失败或响应异常。503 更常见于无可用实例、资源不足或插件拒绝。

建议先看 Gateway 日志中的路由命中和上游地址，再检查：

- 上游进程是否还在运行。
- 上游端口是否能从 Gateway 所在机器访问。
- Nameserver 实例是否健康。
- 自定义 Filter 是否调用了 `context.reject(...)`。
- 内置限流或自定义限流是否返回了 `429/503`。

## 6. 配置修改不生效

配置来源按这个顺序理解：

```text
classpath 默认 YAML
  ↓
外部 config/rover-*.yml
  ↓
Admin runtime overlay
```

如果 `rover.gateway.adminEnabled: true`，Admin 保存过的运行时配置会写入 `config/gateway-runtime.overlay.json`。它会在下次启动时覆盖 YAML 中的同名字段。

如果希望重新以 YAML 为准，可以先停止进程，再确认或移除对应 overlay 文件。

## 7. 排障日志怎么开

默认 INFO 会记录启动、热更新、鉴权失败、上游错误和协议错误。请求完成、路由命中、Nameserver TCP 连接变化默认是 DEBUG。

Logback 示例：

```xml
<logger name="com.rover.gateway.core.filter" level="DEBUG"/>
<logger name="com.rover.nameserver.core.server.NameserverServerHandler" level="DEBUG"/>
```

排查结束后建议恢复默认级别，避免长期高频日志影响磁盘和 I/O。

## 8. 性能问题看哪里

如果问题和吞吐、延迟、503 或 CPU 有关，请看独立的[性能报告与压测方法](./performance-report.zh-CN.md)。性能报告里记录了测试前提、可复现命令和数据解读边界。
