# 服务注册指南

[English](./service-registration.md) · [文档索引](./README.md)

Rover-Suite 为服务提供方提供两条注册路径，它们共用同一个 Nameserver 注册表：

| 服务提供方 | 推荐方式 | 能力边界 |
| :--- | :--- | :--- |
| Spring Boot / Java | `rover-nameserver-starter` + TCP `8888` | 注册生命周期与现有 Java Client 能力 |
| Node.js、Python、Go、长驻 PHP、C++ | 小型 HTTP Registrar + HTTP `8889` | 仅提供方注册、心跳、注销 |

服务提供方按语言选择传输层。Gateway 始终通过现有 TCP 查询/订阅链路发现实例，实际业务流量仍然是普通 HTTP。

## 1. Nameserver 前置配置

Java 注册直接使用默认 TCP 监听器。HTTP 注册需要显式开启：

```yaml
rover:
  nameserver:
    port: 8888
    managePort: 8889
    manageBindHost: 10.0.0.10
    token: "请替换为内网协议 token"
    clientApiEnabled: true
```

`clientApiEnabled` 属于启动配置，修改后要重启 Nameserver。HTTP Registration API（内部配置名称为 Client API）与 `/_manage/**`
共用 `8889`，但是两个鉴权域相互独立：

- `/v1/client/**`：`Authorization: Bearer <rover.nameserver.token>`
- `/_manage/**`：`X-Rover-Admin-Token: <rover.nameserver.adminToken>`

内置配置有意留空 token，便于本地或可信网络零配置接入。HTTP 注册不强制开启鉴权；如果部署跨越信任边界，
可配置非空协议 token、收紧监听地址，并在网络层限制来源。

## 2. Java Spring Boot Starter

### 2.1 安装并引入依赖

当前 `1.0.0-SNAPSHOT` 从源码构建，需要先安装到本地 Maven 仓库：

```bash
mvn clean install -DskipTests
```

然后在业务项目添加：

```xml
<dependency>
    <groupId>com.rover</groupId>
    <artifactId>rover-nameserver-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

无需添加注解，也无需修改启动类。Spring Boot 会从 Starter 自动发现配置。

### 2.2 配置服务提供方

```yaml
spring:
  application:
    name: order-service

server:
  port: 8081

rover:
  nameserver:
    enabled: true
    address: 10.0.0.10:8888
    token: "与 Nameserver 协议 token 一致"
    host: 10.0.1.20
    instance-id: order-pod-7c9f
    weight: 100
    ephemeral: true
    heartbeat-interval-ms: 5000
```

`service-name` 未填时使用 `spring.application.name`，`port` 未填时使用实际 Web Server 端口。
`instance-id` 默认是解析后的 `host:port`，但在地址会被重用的环境中，Pod UID 或部署实例 ID 更安全。
`host` 需要对 Gateway 可达；跨主机或 Pod 时不能使用回环地址。

常用配置：

| `rover.nameserver` 下的键 | 默认值 | 用途 |
| :--- | :--- | :--- |
| `enabled` | `true` | 开启 Starter 生命周期 |
| `address` | `127.0.0.1:8888` | Nameserver TCP 地址 |
| `service-name` | `spring.application.name` | 注册服务名 |
| `instance-id` | 解析后的 `host:port` | 服务内唯一实例键 |
| `host`、`port` | 自动探测 | 向 Gateway 公布的地址 |
| `group`、`zone`、`metadata` | 空 | 部署与自定义元数据；当前版本建议 `group` 保持为空 |
| `weight` | `100` | 负载均衡权重 |
| `ephemeral` | `true` | 断连/过期后摘除 |
| `token` | 空 | Nameserver 协议 token |
| `connect-timeout-ms`、`request-timeout-ms` | `3000` | TCP 操作超时 |
| `heartbeat-interval-ms` | `5000` | 心跳固定间隔 |
| `auto-reconnect`、`reconnect-interval-ms` | `true`、`3000` | 连接恢复 |
| `register-retry-interval-ms` | `5000` | 首次注册固定重试间隔 |

Starter 在 `ApplicationReadyEvent` 后注册，Nameserver 不可用时持续固定重试，重连后重放本地状态，正常关闭时尽力注销。

### 2.3 当前分组边界

注册表身份始终是 `serviceName + instanceId`，`group` 只是查询、订阅和路由过滤字段，不参与实例唯一键。
当前版本的多组快照推送隔离仍在完善：同一 `serviceName` 下同时使用多个非空 group 时，推送缓存可能短时间包含其他组，
随后由周期查询对账修复。如果业务依赖严格分组隔离，建议先完成对应验证；当前单机使用建议保持 `group` 为空。

## 3. HTTP+JSON 注册

HTTP v1 只覆盖临时服务提供方的生命周期：

```text
业务监听端口 ready
  → 提交完整实例信息注册
  → 按固定间隔心跳
  → 优雅退出时注销一次
```

它不是跨语言查询/订阅 SDK，不引入 Agent、Sidecar 或额外 JAR，也没有服务端主动探测。
Nameserver 在内存中保存租约，异常退出的实例由过期扫描摘除。

### 3.1 参考实现

| 语言 | 实现 | 运行边界 |
| :--- | :--- | :--- |
| Node.js | [Registrar](../examples/http-registration/node/rover_registrar.js) | cluster 模式保证唯一 owner |
| Python | [Registrar](../examples/http-registration/python/rover_registrar.py) | 接入应用 lifespan |
| Go | [Registrar 包](../examples/http-registration/go/README.md) | 标准库，Go 1.20+ |
| PHP | [长驻进程指南](../examples/http-registration/php/README.md) | CLI/Swoole/RoadRunner/Octane，不支持请求级 PHP-FPM |
| C++ | [C++20/libcurl 指南](../examples/http-registration/cpp/README.md) | C++20、libcurl、Threads |

这些示例是可直接复制的源码参考，而不是需要分别发布的完整 SDK 包。接入框架时要保留状态机语义。
全部示例入口见 [`examples/http-registration/README.md`](../examples/http-registration/README.md)。

### 3.2 实例身份与所有权

- `serviceName`：路由使用的服务名。
- `instanceId`：服务内唯一、可达的副本；优先使用 Pod UID、容器实例 ID，或在可控环境使用 `host:port`。
- `sessionId`：每次业务进程启动时生成一次的 UUID，三个操作全程复用。

注册表键是 `serviceName + instanceId`。新 session 注册相同键时接管所有权（last-register-wins），旧 session 心跳或注销会收到
`409 STALE_SESSION`。sessionId 不能判断两个并发启动的进程谁更新，所以每个在线副本需要使用唯一 `instanceId`。

### 3.3 生命周期行为

参考实现统一使用：

- 业务端口 ready 后立即注册。
- 注册失败后固定等待 5 秒重试，不使用指数退避。
- 注册成功后以 fixed-delay 每 5 秒心跳。
- 单次请求默认超时 3 秒，任何时刻最多一个请求在途。
- 网络错误、`408`、`429`、`5xx`：等待固定间隔后重试。
- 只有心跳同时收到 `404` 且 code 精确为 `INSTANCE_NOT_FOUND`：立即提交完整注册。
- 其他 `4xx`，包括通用 `404 NOT_FOUND` 与 `409 STALE_SESSION`：停止当前 Registrar，暴露永久错误。
- 优雅退出：先停止调度，再尽力注销一次，不无限阻塞业务进程退出。

可配置的参考实现会拒绝超过 8 秒的重试或心跳间隔。按默认 30 秒过期阈值与 5 秒扫描周期，HTTP 进程异常退出后通常会在最后一次成功心跳后约 30–35 秒摘除。

### 3.4 手工协议验收

下面的命令假设 `rover.nameserver.token` 已设为 `rover-dev-token`。整个流程要使用同一个 UUID。
`18080` 仅用于协议验收；真实注册前需要先启动业务监听器。

```bash
export ROVER_NAMESERVER_TOKEN='rover-dev-token'

curl -i -X POST http://127.0.0.1:8889/v1/client/instances/register \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${ROVER_NAMESERVER_TOKEN}" \
  --data '{"serviceName":"http-demo","instanceId":"local-18080","sessionId":"11f1b8a4-f588-4a68-a74d-34354013ac4d","host":"127.0.0.1","port":18080,"weight":100,"metadata":{"language":"curl"}}'

curl -i -X POST http://127.0.0.1:8889/v1/client/instances/heartbeat \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${ROVER_NAMESERVER_TOKEN}" \
  --data '{"serviceName":"http-demo","instanceId":"local-18080","sessionId":"11f1b8a4-f588-4a68-a74d-34354013ac4d"}'

curl -i -X POST http://127.0.0.1:8889/v1/client/instances/unregister \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${ROVER_NAMESERVER_TOKEN}" \
  --data '{"serviceName":"http-demo","instanceId":"local-18080","sessionId":"11f1b8a4-f588-4a68-a74d-34354013ac4d"}'
```

成功需要 HTTP `200` 且 JSON `code: "OK"`。完整字段约束、响应结构与稳定机器码以
[OpenAPI v1 契约](../rover-nameserver-core/src/main/resources/openapi/rover-registration-v1.yaml)为准。

### 3.5 多 worker 规则

一个对外可达端点或 Pod 只能有一个 Registrar owner。Node cluster、Gunicorn/uWSGI、RoadRunner、Octane 等 worker
不能全部注册相同 `serviceName + instanceId`。应将 Registrar 放在 master/容器生命周期中；只有每个 worker 确实拥有唯一、可直接访问的端口和实例 ID 时才分别注册。

普通 PHP-FPM 请求生命周期不能维护后台心跳。PHP 参考只能在长驻进程中使用：`run()` 会阻塞；如果已有事件循环，可先 `start()`，再每 50–100ms 调用 `tick()`，退出时调用 `close()`。

## 4. 恢复模型

- Nameserver 只在内存中保存在线注册。
- Nameserver 重启后注册表为空，并生成新的诊断 `epoch`。
- Java 客户端重连并重放本地记忆的注册。
- HTTP 客户端通过心跳 `INSTANCE_NOT_FOUND` 发现租约丢失，然后立即重新注册。
- Nameserver 不会因为某个地址以前注册过就恢复它。
- Nameserver 不会主动调用服务提供方的健康接口；客户端上报与 TTL 决定存活。
- Nameserver 收到注销后会立即移除实例；当前 Gateway 对最后实例的空推送带保护，本地缓存最迟在下一次周期对账时清空，默认最多约 30 秒。

这种设计接受短暂的重注册窗口，避免恢复陈旧地址。权衡说明见[架构文档](./architecture.zh-CN.md#4-软状态租约与纯内存恢复)。

## 5. 注册排障

| 结果 | 处理方式 |
| :--- | :--- |
| `401 UNAUTHORIZED` | 确保 Bearer token 与 Nameserver `rover.nameserver.token` 一致。 |
| `404 NOT_FOUND` | 开启 `clientApiEnabled`、检查端口/路径并重启 Nameserver；避免无限重试错误端点。 |
| 心跳 `404 INSTANCE_NOT_FOUND` | 立即使用同一 session 和完整实例信息重新注册。 |
| `409 STALE_SESSION` | 停止旧 owner，修正重复 `instanceId`。 |
| 实例反复过期 | 保持 5 秒默认心跳，检查超时/网络，避免重叠调度器。 |
| Gateway 无法访问实例 | 公布 Gateway 网络可达的 host 和 port。 |

如需修改注册链路，请阅读[二次开发指南](./development-guide.zh-CN.md#6-注册扩展)。
