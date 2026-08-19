# 使用指南

[English](./user-guide.md) · [文档索引](./README.md)

本文说明 Rover-Suite 的日常配置与运行。如果还没有跑过 demo，请先阅读[快速上手](./quick-start.zh-CN.md)。
服务提供方的完整生命周期见[服务注册指南](./service-registration.zh-CN.md)。

## 1. 进程与端口

| 进程或监听器 | 默认值 | 用途 |
| :--- | :--- | :--- |
| Nameserver TCP | `8888` | Java 注册、Gateway 查询/订阅、实例推送 |
| Nameserver HTTP | `8889` | `/_manage/**` 与可选的 `/v1/client/**` 注册 |
| Gateway HTTP | 内置 YAML 为 `80` | 业务流量和 Gateway 管理接口；本地建议改为 `8080` |
| demo backend | `8081` | 示例 `demo-service` |
| Admin | `9090` | 可选 Web 管理台 |

Nameserver HTTP 不是独立组件。将 `managePort` 设为 `0` 会同时关闭管理 API 和 HTTP Registration API（内部配置名称为 Client API）。

## 2. 配置加载

独立进程的启动配置优先级为：

```text
工作目录 config/<file>.yml
  > classpath <file>.yml
  > 代码默认值
```

主要内置配置文件：

- [`rover-nameserver.yml`](../rover-nameserver-bootstrap/src/main/resources/rover-nameserver.yml)
- [`rover-gateway.yml`](../rover-gateway-bootstrap/src/main/resources/rover-gateway.yml)

修改配置前，建议将完整内置文件复制到 `./config/`。外部文件会成为启动配置源，不会与 classpath
文件按文本逐项合并。当前独立 YAML loader 不展开 `${ENV_VAR}`；请通过受保护的挂载文件或部署流程生成配置，不要提交真实 token。

YAML 加载后，已支持的运行时配置可能被以下文件覆盖：

- `config/nameserver-runtime.overlay.json`
- `config/gateway-runtime.overlay.json`
- `config/routes.overlay.json`

`routes.overlay.json` 一旦存在，会整体替代 YAML 路由列表。YAML 路由修改不生效时，请优先检查此文件。
监听端口、绑定地址、token、发现类型、HTTP Registration API 开关、插件目录与 CORS 都属于启动配置，修改后需重启。

## 3. 配置 Nameserver

一份面向生产的基础配置如下：

```yaml
rover:
  nameserver:
    port: 8888
    bindHost: 10.0.0.10
    managePort: 8889
    manageBindHost: 10.0.0.10
    token: "请替换为协议 token"
    adminToken: "请替换为不同的管理 token"
    clientApiEnabled: false
    heartbeatTimeoutMillis: 15000
    healthCheckIntervalMillis: 5000
    instanceExpireMillis: 30000
    pushEnabled: true
```

只有非 Java 服务需要 HTTP 注册时才开启 `clientApiEnabled`。在线实例是纯内存的租约软状态；Nameserver 不会主动探测业务端口，也不恢复历史注册。设计原因见[架构说明](./architecture.zh-CN.md)。

## 4. 配置 Gateway 发现

### Nameserver 动态发现

```yaml
rover:
  gateway:
    port: 8080
    discovery:
      type: nameserver
      nameserver:
        address: 10.0.0.10:8888
        token: "与 Nameserver 协议 token 一致"
        reconcileIntervalMs: 30000
    loadbalance:
      strategy: round_robin
```

Gateway 的 token 键是 `rover.gateway.discovery.nameserver.token`，值要与 Nameserver 的
`rover.nameserver.token` 一致。Gateway 保持本地实例缓存，接收快照推送，并定期查询对账；业务请求不会每次同步访问 Nameserver。

### 静态上游

不需要注册发现时可以使用静态模式：

```yaml
rover:
  gateway:
    discovery:
      type: static
    routes:
      - id: static-orders
        businessPrefix: /orders
        targetUrls:
          - http://10.0.1.10:8080
          - http://10.0.1.11:8080|200
        stripPrefix: /orders
```

`|200` 后缀是可选权重。静态上游与 Nameserver 发现共用
`round_robin`、`random`、`weighted_round_robin`、`ip_hash`、`least_connections` 五种负载均衡策略。
当前静态上游只使用 URL 的 scheme、host 与 port；不要在 `targetUrl` / `targetUrls` 中配置基路径，路径变换统一使用路由的 `stripPrefix`。

## 5. 定义路由

Nameserver 动态路由示例：

```yaml
routes:
  - id: order-api
    businessPrefix: /api/orders
    serviceName: order-service
    stripPrefix: /api
```

| 字段 | 含义 |
| :--- | :--- |
| `id` | 唯一路由标识 |
| `businessPrefix` | 匹配入站请求的路径前缀 |
| `serviceName` | 动态发现时的 Nameserver 服务名 |
| `group` | 可选分组过滤；当前版本多组推送隔离仍在收口，建议留空 |
| `targetUrls` | 静态路由的固定上游列表 |
| `stripPrefix` | 转发前移除的前缀；设为 `""` 保留完整路径 |

一条路由通常根据当前发现模式二选一使用 `serviceName` 或 `targetUrls`。

## 6. 注册服务提供方

- Spring Boot 服务：使用 Starter，无需修改启动类。参见
  [Java Starter 注册](./service-registration.zh-CN.md#2-java-spring-boot-starter)。
- Node.js、Python、Go、长驻 PHP 或 C++：使用小型 HTTP Registrar。参见
  [HTTP 注册](./service-registration.zh-CN.md#3-httpjson-注册)。

当前 Maven 坐标使用 `1.0.0-SNAPSHOT`，不应视为已发布到公共仓库。独立本地项目引用 Starter 前，
请先在本源码仓库执行 `mvn clean install -DskipTests`；正式发布后再替换为对应版本。

## 7. 管理接口与 Admin

Nameserver 本地内置配置的 `adminToken` 为空，因此以下请求无需请求头：

```bash
curl http://127.0.0.1:8889/_manage/status
curl http://127.0.0.1:8889/_manage/instances
```

`adminToken` 非空时，管理请求需要：

```text
X-Rover-Admin-Token: <adminToken>
```

Bearer 协议 token 不能代替管理请求头，管理请求头也不能访问 `/v1/client/**`。需要鉴权隔离时，为两个域配置不同 token。

当前内置管理端点：

| 组件 | 路径与方法 | 用途 |
| :--- | :--- | :--- |
| Gateway | `GET /_manage/status` | 监听端口、发现类型、路由与运行时状态 |
| Gateway | `GET/PUT/POST/DELETE /_manage/routes` | 查看、整表替换、新增/更新或删除路由 |
| Gateway | `GET/POST /_manage/configs` | 查看或更新已登记的运行时配置 |
| Gateway | `GET /_manage/metrics`、`/metrics/live`、`/metrics/selfcheck`、`/prometheus` | JSON 指标、1 秒轻量实时快照（`range=60|300`）、自检与 Prometheus 文本 |
| Gateway | `GET /_manage/traces` | 有界请求时间线；支持 `traceId`、`path`、`slow` 查询参数 |
| Nameserver | `GET /_manage/status`、`/instances` | 运行状态与当前内存实例 |
| Nameserver | `GET/POST /_manage/configs` | 查看或更新已登记的运行时配置 |
| Nameserver | `GET /_manage/metrics`、`/metrics/live`、`/events` | 注册指标、轻量实时快照与近期事件 |

Rover-Admin 是可选组件：

```bash
mvn -pl rover-admin spring-boot:run
```

打开 `http://127.0.0.1:9090`。仪表盘每秒拉 `/api/live` 看瞬时流量与 JVM，约 15 秒刷新组件状态。
请求追踪遵循 Gateway `gateway.trace.sampleRate`：`0` 只记慢请求（默认）；在 Admin 配置里改成 `1`
即可采集普通流量，无需重启。

若 Gateway 不在默认端口（仓库常见默认是 `80`），请把 Rover-Admin 的 Gateway 地址改成对应监听口。

## 8. 可选部署加固

Rover 默认采用全网卡监听与空 token，目的是本地或可信网络零配置启动；项目不强制一套安全策略。
部署跨越信任边界时，可按需选择以下措施：

- 将 `8888` 和 `8889` 绑定到内网地址，并通过网络策略或防火墙限制来源。
- 为协议面和管理面配置非空、不同的 token。
- 未使用 HTTP 注册时保持 `/v1/client/**` 关闭。
- Gateway `/_manage/**` 与业务流量共用 Gateway 监听端口。要配置非空 Gateway `adminToken`，并在外层代理/ACL 中阻断不应远程访问的管理路径。
- TCP `8888`、Nameserver HTTP 和 Gateway HTTP 都没有内置 TLS。token 只做鉴权，不提供加密：TCP 放在私网/VPN/TLS 隧道，HTTP 在可信代理上终止 HTTPS。
- 注册 Gateway 真正可达的地址；跨主机或 Pod 不要使用 `127.0.0.1`。
- 每个可并发访问的副本必须使用唯一 `instanceId`。
- 业务端口 ready 后再注册，优雅退出时关闭 Registrar。
- 监控注册失败、过期摘除、可用实例数和 Gateway 上游失败。

Rover-Suite 当前面向小团队的单机或可信网络部署，不是面向公网的多租户控制面。需要对外暴露控制端口时，
由部署方选择相应加固方式；边界说明见[架构非目标](./architecture.zh-CN.md#7-明确非目标)。

## 9. 当前运行边界

- 当前是单节点 `1.0.0-SNAPSHOT`，不提供 Nameserver 高可用或在线实例持久化恢复。
- 发现链路是“推送优先、周期查询对账兜底”，不是强实时一致。最后一个实例注销或过期时，空推送当前会被 Gateway 保护，
  本地缓存最迟在下一次对账时清空，默认最长约 30 秒；窗口内请求可能命中刚退出的地址。
- 如果 Gateway 启动时 Nameserver 不可用，初始订阅失败后可能等到下一次对账才补齐，默认最长约 30 秒。
- 同一服务多组推送隔离仍在收口，当前建议 `group` 留空。具体说明见[服务注册指南](./service-registration.zh-CN.md#23-当前分组边界)。
- 持久实例全部被标记为不健康时，Gateway 当前会退回全部缓存实例继续尝试，属于 fail-open 行为。
- Gateway 聚合完整请求与响应，不支持 WebSocket、SSE 或流式代理；默认请求体上限 1 MiB，响应体硬上限 16 MiB。
- 静态上游 URL 只保留 scheme、host 与 port，不保留 URL 基路径。

这些边界不影响普通单机 HTTP API 与默认空 group 场景，但对强一致摘除、分组隔离、流式协议或公网控制面有要求时需要评估。

## 10. 常见问题

| 现象 | 常见原因 |
| :--- | :--- |
| 修改配置不生效 | 进程的工作目录不对、运行时 overlay 覆盖，或该配置需要重启。 |
| Java 服务鉴权失败 | 应用的 `rover.nameserver.token` 与 Nameserver 不一致。 |
| Gateway 无法发现服务 | 检查 `rover.gateway.discovery.nameserver.address` 和 `.token`；它们是 Gateway 配置，不是 Starter 配置。 |
| Nameserver 已恢复但 Gateway 仍无实例 | 当前初始订阅失败可能等到下一次对账；等待 `reconcileIntervalMs` 或重启 Gateway。 |
| 最后一个实例退出后仍短暂收到转发 | Gateway 的空快照保护等待下一次对账清空，默认最长约 30 秒。 |
| HTTP Registrar 收到 `404 NOT_FOUND` | `clientApiEnabled` 未开启、路径错误或 HTTP 监听器未启动。开启 Registration API 后需重启。 |
| HTTP Registrar 收到 `409 STALE_SESSION` | 另一个进程注册了相同 `serviceName + instanceId`。每副本应使用唯一 ID，每端点只有一个 owner。 |
| 实例可见但不可达 | 注册的 host/port 对 Gateway 不可达。 |
| YAML 路由被忽略 | `config/routes.overlay.json` 正在整体覆盖 YAML 路由。 |

延伸阅读：[服务注册指南](./service-registration.zh-CN.md)、[架构说明](./architecture.zh-CN.md)、
[二次开发指南](./development-guide.zh-CN.md)。
