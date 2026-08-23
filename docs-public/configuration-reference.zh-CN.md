# 配置项参考

外部 `config/` 文件优先于 classpath 默认配置。启动配置修改后通常需要重启；Admin 中的运行时配置会写入对应的
`*-runtime.overlay.json`，并由组件决定是否立即应用。最终支持项、当前值、默认值和 `hotReloadable` 标记以
`GET /api/configs` 返回为准。

## Admin 启动配置

| 配置 | 默认值 | 说明 | 生效方式 |
| --- | --- | --- | --- |
| `server.port` | `9090` | Admin HTTP 端口 | 重启 |
| `rover.admin.gateway-url` | `http://127.0.0.1:80` | Gateway 管理口基地址 | 重启 |
| `rover.admin.nameserver-manage-url` | `http://127.0.0.1:8889` | Nameserver 管理口基地址 | 重启 |
| `rover.admin.admin-token` | 空 | Admin 调用下游时发送的 `X-Rover-Admin-Token` | 重启 |

Admin 本身默认不持有业务配置，也不为 `/api/*` 自动增加登录认证；生产环境需要通过网络 ACL、反向代理或 VPN 保护
`server.port`。

## Gateway 启动配置

| 配置 | 默认值 | 说明 | 生效方式 |
| --- | --- | --- | --- |
| `rover.gateway.port` | `80` | 业务 HTTP 端口 | 重启 |
| `rover.gateway.adminToken` | 空 | `/_manage/**` 管理口 token | 重启 |
| `rover.gateway.server.bindHost` | `0.0.0.0` | 业务监听地址 | 重启 |
| `rover.gateway.server.maxContentLengthBytes` | `1048576` | 请求体上限 | 重启 |
| `rover.gateway.proxy.connectTimeoutMillis` | `3000` | 上游连接超时 | 重启 |
| `rover.gateway.proxy.requestTimeoutMillis` | `30000` | 上游请求超时的启动默认值 | 重启 |
| `rover.gateway.discovery.type` | `STATIC`（代码默认）/ 示例为 `nameserver` | `static` 或 `nameserver` | 重启 |
| `rover.gateway.discovery.nameserver.address` | `127.0.0.1:8888` | Nameserver TCP 地址 | 重启 |
| `rover.gateway.discovery.nameserver.reconcileIntervalMs` | `30000` | 本地实例缓存周期对账间隔 | 重启 |
| `rover.gateway.discovery.nameserver.token` | 空 | Gateway 访问 Nameserver 的协议 token | 重启 |
| `rover.gateway.filters.enabled` | `true` | Filter 启动总开关 | 重启；运行时另见下表 |
| `rover.gateway.filters.pluginDir` | `plugins` | Filter/LoadBalancer JAR 目录 | 重启 |
| `rover.gateway.filters.classes` | `[]` | 显式加载的 Filter 全限定类名 | 重启 |
| `rover.gateway.rewrite.stripPrefix` | 空 | 全局重写前缀 | 重启 |
| `rover.gateway.cors.enabled` | `false`（代码默认）/ 示例为 `true` | CORS 开关 | 重启 |
| `rover.gateway.cors.allowedOrigins` | `[]` | 允许的来源；`*` 仅建议开发环境 | 重启 |
| `rover.gateway.cors.allowedMethods` | `[]` | 允许的 HTTP 方法 | 重启 |
| `rover.gateway.cors.allowedHeaders` | `[]` | 允许的请求头；`*` 表示任意 | 重启 |
| `rover.gateway.cors.credentials` | `false` | 是否允许凭证 | 重启 |
| `rover.gateway.cors.maxAgeSeconds` | `1800` | CORS 预检缓存秒数 | 重启 |

### 路由字段

`rover.gateway.routes` 是路由数组，不是单值配置。每项支持：`id`、`businessPrefix`、`serviceName`、`group`、
`targetUrl`、`targetUrls`、`stripPrefix`。动态发现填写 `serviceName`（可配 `group`）；静态模式填写
`targetUrl` 或 `targetUrls`。`targetUrls` 的元素可使用 `http://host:port|weight` 指定权重。Admin 保存的路由会写入
`config/routes.overlay.json`，并整体替换启动 YAML 中的路由列表。

## Gateway 运行时配置（Admin 可热更新）

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `gateway.loadbalance.strategy` | `round_robin` | 内置策略、SPI `name()` 或实现类全名 |
| `gateway.request.timeoutMillis` | `30000` | 网关请求超时（毫秒） |
| `gateway.filter.enabled` | `true` | Filter 链开关；注意这里是 `filter` 单数 |
| `gateway.metrics.enabled` | `true` | 指标采集总开关 |
| `gateway.metrics.windowSeconds` | `300` | 指标滑动窗口，最大 300 秒 |
| `gateway.trace.enabled` | `true` | 请求链路时间线开关 |
| `gateway.trace.slowThresholdMillis` | `100` | 超过该值记录慢请求时间线 |
| `gateway.trace.sampleRate` | `0.0` | `0` 只记录慢请求，`1` 全量记录，支持 `0~1` 小数 |

这些键会落盘到 `config/gateway-runtime.overlay.json`。新增或替换插件 JAR、修改端口、监听地址、token、发现类型、
插件目录和 CORS 等启动配置不能只依赖热更新，应重启 Gateway。

## Nameserver 启动配置

| 配置 | 默认值 | 说明 | 生效方式 |
| --- | --- | --- | --- |
| `rover.nameserver.port` | `8888` | TCP 注册/发现/订阅端口 | 重启 |
| `rover.nameserver.bindHost` | `0.0.0.0` | TCP 监听地址 | 重启 |
| `rover.nameserver.managePort` | `8889` | HTTP 管理与客户端 API 端口 | 重启 |
| `rover.nameserver.manageBindHost` | `0.0.0.0` | HTTP 监听地址 | 重启 |
| `rover.nameserver.token` | 空 | 注册/订阅协议 token | 重启 |
| `rover.nameserver.adminToken` | 空 | `/_manage/**` 管理 token | 重启 |
| `rover.nameserver.clientApiEnabled` | `false` | 是否启用 `/v1/client/**` HTTP+JSON 注册 API | 重启 |
| `rover.nameserver.writeAckMode` | `SINGLE` | `SINGLE`/`HALF`/`ALL`；当前单机主要用于协议语义 | 重启 |
| `rover.nameserver.allowClientAckOverride` | `false` | 是否允许客户端覆盖 ACK 强度 | 重启 |
| `rover.nameserver.cluster.enabled` | `false` | 集群预留开关，当前应保持关闭 | 重启 |
| `rover.nameserver.cluster.nodeId` | 空 | 预留节点 ID | 重启 |
| `rover.nameserver.cluster.nodes` | `[]` | 预留集群节点地址 | 重启 |
| `rover.nameserver.cluster.replicationFactor` | `1` | 预留副本数 | 重启 |

## Nameserver 运行时配置（Admin 可热更新）

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `nameserver.health.checkIntervalMillis` | `5000` | 健康检查扫描间隔 |
| `nameserver.heartbeat.timeoutMillis` | `15000` | 心跳超时；实例超过后标记不健康 |
| `nameserver.instance.expireMillis` | `30000` | 临时实例过期并剔除时间 |
| `nameserver.push.enabled` | `true` | 服务变更推送开关 |

这些键会落盘到 `config/nameserver-runtime.overlay.json`。运行时修改仍受组件校验和实例状态影响。
