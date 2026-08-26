# 配置项参考

外部 `config/` 文件优先于 classpath 默认配置。启动配置修改后通常需要重启；Admin 中的运行时配置会写入对应的
`*-runtime.overlay.json`，并由组件决定是否立即应用。最终支持项、当前值、默认值和 `hotReloadable` 标记以
`GET /api/configs` 返回为准。

## 进程级安全开关（非 YAML）

| 开关 | 说明 |
| --- | --- |
| 环境变量 `ROVER_STRICT_SECURITY=true` | 空 `adminToken` / Nameserver 协议 `token` 时**拒绝启动**（本地默认不设，仅 WARN） |
| JVM `-Drover.strictSecurity=true` | 同上 |

探活：`GET /_manage/health` → `{"status":"UP","component":"..."}`（鉴权规则与其它管理口相同）。

生产样例见 [`deploy/production/`](../deploy/production/)。

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
| `rover.gateway.adminEnabled` | `true` | 是否启用 `/_manage/**`、Admin 路由 overlay 与运行时配置 overlay | 重启 |
| `rover.gateway.adminToken` | 空 | `/_manage/**` 管理口 token；空=不鉴权（启动 WARN）。正式机建议非空，或设 `ROVER_STRICT_SECURITY=true` | 重启 |
| `rover.gateway.server.bindHost` | `0.0.0.0` | 业务监听地址 | 重启 |
| `rover.gateway.server.maxContentLengthBytes` | `1048576` | 请求体上限（入站管道计数，超限 413）。不再经过 `HttpObjectAggregator` | 重启 |
| `rover.gateway.server.dispatchOnEventLoop` | `false` | 业务 Handler 是否留在 EventLoop。默认走业务线程池，和收发包分开。确认无阻塞才改 `true` | 重启；也可用 `-Drover.gateway.dispatchOnEventLoop` |
| `rover.gateway.server.ioTransport` | `auto` | 入站 EventLoop 与出站 Channel 用同一套 I/O。`auto`：Linux 选 Epoll（Docker Linux 容器里一般是这个），macOS 上直接运行进程选 KQueue，原生库不可用时退 NIO。也可写死 `nio` / `epoll` / `kqueue`（不可用时退 NIO） | 重启；也可用 `-Drover.gateway.ioTransport` |
| `rover.gateway.metrics.enabled` | `true` | 启动时指标总开关；`false` 热路径不记 inflight/record。503 拒绝计数仍记 | 重启；Admin 关闭时只认 YAML |
| `rover.gateway.metrics.windowSeconds` | `300` | 指标滑动窗口（秒） | 重启 / 运行时 |
| `rover.gateway.trace.enabled` | `true` | 启动时时间线开关；`false` 不 `markPhase`、不造 traceId | 重启；Admin 关闭时只认 YAML |
| `rover.gateway.trace.slowThresholdMillis` | `100` | 慢请求阈值 | 重启 / 运行时 |
| `rover.gateway.trace.sampleRate` | `0.0` | 采样率 0~1 | 重启 / 运行时 |
| `rover.gateway.proxy.outbound` | `netty` | 出站客户端。默认 `netty` 只转发 `http://` 上游。`jdk` 是第一版 JDK `HttpClient`（强制 HTTP/1.1），主要用于回滚和对照；它自带 TLS，因此上游必须是 `https://` 时可以切过去。也可用 `-Drover.gateway.proxy.outbound` | 重启 |
| `rover.gateway.proxy.connectTimeoutMillis` | `3000` | 上游连接超时 | 重启 |
| `rover.gateway.proxy.requestTimeoutMillis` | `30000` | 上游请求超时的启动默认值 | 重启 |
| `rover.gateway.discovery.type` | `STATIC`（代码默认）/ 示例为 `nameserver` | `static`、`nameserver` 或 `nacos` | 重启 |
| `rover.gateway.discovery.nameserver.address` | `127.0.0.1:8888` | Nameserver TCP 地址 | 重启 |
| `rover.gateway.discovery.nameserver.reconcileIntervalMs` | `30000` | 本地实例缓存周期对账间隔 | 重启 |
| `rover.gateway.discovery.nameserver.token` | 空 | Gateway 访问 Nameserver 的协议 token | 重启 |
| `rover.gateway.filters.enabled` | `true` | Filter 启动总开关 | 重启；运行时另见下表 |
| `rover.gateway.filters.pluginDir` | `plugins` | Filter/LoadBalancer JAR 目录 | 重启 |
| `rover.gateway.filters.accessLog` | `true` | 是否装配访问日志过滤器；内容为 debug（默认 INFO 不刷屏） | 重启 |
| `rover.gateway.filters.classes` | `[]` | 显式加载的 Filter 全限定类名 | 重启 |
| `rover.gateway.rateLimit.enabled` | `false` | Gateway 本地限流开关；按实例生效 | 重启 |
| `rover.gateway.rateLimit.algorithm` | `token_bucket` | `token_bucket` 或 `sliding_window` | 重启 |
| `rover.gateway.rateLimit.key` | `path` | `global` 全实例或 `path` 按请求路径 | 重启 |
| `rover.gateway.rateLimit.permitsPerSecond` | `1000` | 令牌桶补充速率 | 重启 |
| `rover.gateway.rateLimit.burst` | `2000` | 令牌桶容量/突发上限 | 重启 |
| `rover.gateway.rateLimit.limit` | `1000` | 滑动窗口最大请求数 | 重启 |
| `rover.gateway.rateLimit.windowSeconds` | `1` | 滑动窗口长度，范围 1~3600 秒 | 重启 |
| `rover.gateway.circuitBreaker.enabled` | `false` | 进程内熔断开关；按上游 `host:port` 连续失败计数 | 重启 |
| `rover.gateway.circuitBreaker.failureThreshold` | `5` | 连续失败几次后打开 | 重启 |
| `rover.gateway.circuitBreaker.openSeconds` | `10` | 打开后休息秒数，范围 1~3600 | 重启 |
| `rover.gateway.circuitBreaker.recovery` | `all` | `all`=到期全开；`half`=只发一个探测 | 重启 |
| `rover.gateway.retry.enabled` | `false` | 连不上时换下一台；只救还没发出去的连接失败 | 重启 |
| `rover.gateway.rewrite.stripPrefix` | 空 | 全局重写前缀 | 重启 |
| `rover.gateway.cors.enabled` | `false`（代码默认）/ 示例为 `true` | CORS 开关 | 重启 |
| `rover.gateway.cors.allowedOrigins` | `[]` | 允许的来源；`*` 仅建议开发环境 | 重启 |
| `rover.gateway.cors.allowedMethods` | `[]` | 允许的 HTTP 方法 | 重启 |
| `rover.gateway.cors.allowedHeaders` | `[]` | 允许的请求头；`*` 表示任意 | 重启 |
| `rover.gateway.cors.credentials` | `false` | 是否允许凭证 | 重启 |
| `rover.gateway.cors.maxAgeSeconds` | `1800` | CORS 预检缓存秒数 | 重启 |

本地限流只维护 Gateway 进程内状态，不访问 Nameserver 或外部存储；多实例部署时每个 Gateway 独立计数。复杂的用户、租户或全局分布式限流，请关闭该开关并使用自定义 Filter 插件。

进程内熔断同样只记本机状态，默认关闭。打开后按实例 `host:port` 连续失败计数：连接失败、超时、上游 5xx 算失败；2xx/4xx 算活着并清零。选点时跳过打开的实例；全被打开则回 `503`，原因头 `CIRCUIT_OPEN`。它不是独立 Filter，挂在选点和转发收尾，全健康时只多读一个整数。多 Gateway 不共享状态。

换台重试默认关闭，和熔断分开。打开后只在「连接失败、请求还没发出去、旁边还有另一台」时再打一枪。上游 5xx、请求超时、已经写出响应头、POST body 已经流走，都不换台。最多换 1 次。客户端业务重试仍然要自己做。换台次数在 `/_manage/metrics` 的 `resources.retries.connect` 和 Prometheus `rover_gateway_retries_total{reason="connect"}`。

`rover.gateway.adminEnabled: false` 适合不部署 Rover-Admin、只把 YAML 当作配置事实来源的环境。它会跳过
`config/routes.overlay.json` 和 `config/gateway-runtime.overlay.json`，并使 `/_manage/**` 返回 `404`；不会影响 Gateway
连接 Nameserver、订阅服务或转发业务请求。已有 overlay 文件不会被删除，重新设为 `true` 后仍会继续生效。

Gateway 回 503 时会带响应头 `X-Rover-Reject-Reason`：`INFLIGHT_LIMIT`（在途闸门满，默认 `max(64, CPU×8)`，可用
`-Drover.gateway.maxInflight` 覆盖）、`NO_UPSTREAM`（没有可用上游）或 `CIRCUIT_OPEN`（候选实例都被熔断打开）。日志里会打 `inflight=已用/上限`。
`/_manage/metrics` 的 `resources.rejects` 和 Prometheus `rover_gateway_rejects_total` 按原因计数。
这些只发生在拒绝路径，成功请求不加活。

### 路由字段

`rover.gateway.routes` 是路由数组，不是单值配置。每项支持：`id`、`businessPrefix`、`serviceName`、`group`、
`targetUrl`、`targetUrls`、`stripPrefix`。动态发现填写 `serviceName`（可配 `group`）；静态模式填写
`targetUrl` 或 `targetUrls`。`targetUrls` 的元素可使用 `http://host:port|weight` 指定权重。默认 `outbound=netty` 时上游必须是 `http://`。写入 `https://` 会在启动或热更新时被拒绝，避免配置通过、请求才失败；若上游确实是 HTTPS，先把 `proxy.outbound` 设为 `jdk` 再重启。Admin 保存的路由会写入
`config/routes.overlay.json`，并整体替换启动 YAML 中的路由列表。

## Gateway 运行时配置

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `gateway.loadbalance.strategy` | `round_robin` | 启动/插件装配配置；内置策略、SPI `name()` 或实现类全名；不在 Admin 中修改 |
| `gateway.request.timeoutMillis` | `30000` | Gateway 请求超时（毫秒） |
| `gateway.filter.enabled` | `true` | Filter 链开关；注意这里是 `filter` 单数 |
| `gateway.rateLimit.enabled` | `false` | 开启 Gateway 内置本地限流 |
| `gateway.rateLimit.algorithm` | `token_bucket` | `token_bucket` 或 `sliding_window` |
| `gateway.rateLimit.key` | `path` | `global` 整台 Gateway 共用配额；`path` 按请求路径分别计数 |
| `gateway.rateLimit.permitsPerSecond` | `1000` | 令牌桶每秒补充令牌数（请求/秒） |
| `gateway.rateLimit.burst` | `2000` | 令牌桶最大容量（请求数） |
| `gateway.rateLimit.limit` | `1000` | 一个滑动窗口内允许的最大请求数 |
| `gateway.rateLimit.windowSeconds` | `1` | 滑动窗口时长（秒） |
| `gateway.circuitBreaker.enabled` | `false` | 开启进程内熔断 |
| `gateway.circuitBreaker.failureThreshold` | `5` | 连续失败几次后打开 |
| `gateway.circuitBreaker.openSeconds` | `10` | 打开后休息秒数 |
| `gateway.circuitBreaker.recovery` | `all` | `all`=到期全开；`half`=只发一个探测 |
| `gateway.retry.enabled` | `false` | 连不上时换下一台 |
| `gateway.metrics.enabled` | `true` | 指标采集总开关；`false` 时热路径不记 inflight/record |
| `gateway.metrics.windowSeconds` | `300` | 指标滑动窗口，最大 300 秒 |
| `gateway.trace.enabled` | `true` | 请求链路时间线开关；`false` 时不 `markPhase`、不造 traceId |
| `gateway.trace.slowThresholdMillis` | `100` | 超过该值记录慢请求时间线 |
| `gateway.trace.sampleRate` | `0.0` | `0` 只记录慢请求，`1` 全量记录，支持 `0~1` 小数 |

实现 `ConfigurablePlugin` 的插件会额外声明 `gateway.plugin.<namespace>.<key>` 形式的可热更新配置。具体字段、默认值、
候选项和校验规则由插件自己声明，只有插件已装配时才显示在 Admin；其值与 Gateway 配置共用同一个 overlay 落盘。这里的热更新
不包含插件 JAR 的新增、替换或删除。

内置限流任一字段变更都会原子重建过滤器链：在途请求继续使用原 Filter，后续请求立即使用新限流器。限流只在每个
Gateway 进程内独立计数，不是分布式全局配额。

熔断开关变更会重建过滤器链（关着时选点路径不持有熔断器）。阈值、休息时间和 `recovery` 改的是同一份配置对象，不重建熔断状态。

除 `gateway.loadbalance.strategy` 外，这些可热更新键会落盘到 `config/gateway-runtime.overlay.json`。新增或替换插件 JAR、修改端口、监听地址、token、发现类型、
插件目录和 CORS 等启动配置不能只依赖热更新，应重启 Gateway。

### 覆盖优先级与注意事项

`adminEnabled: true` 时，启动顺序为 **YAML 基线 → Gateway overlay**。如果曾在 Admin 保存过配置，overlay 中同名值会
在下次启动时覆盖 YAML；Admin 不会因“仅打开页面”而写入该文件。当前 overlay 是全量快照，所以一次保存可能同时保留其他
键当时的旧值。修改 YAML 后未生效时，优先检查 `config/gateway-runtime.overlay.json`；路由问题则检查
`config/routes.overlay.json`（它会整体替代 YAML 路由）。

`gateway.loadbalance.strategy` 不通过 Admin 修改，也不会写入 `config/gateway-runtime.overlay.json`。自定义 LoadBalancer 请在
`rover-gateway.yml` 中配置 SPI `name()` / 实现类全名并重启 Gateway，避免 Admin 覆盖插件装配策略。

## Nameserver 启动配置

| 配置 | 默认值 | 说明 | 生效方式 |
| --- | --- | --- | --- |
| `rover.nameserver.port` | `8888` | TCP 注册/发现/订阅端口 | 重启 |
| `rover.nameserver.bindHost` | `0.0.0.0` | TCP 监听地址 | 重启 |
| `rover.nameserver.managePort` | `8889` | HTTP 管理与客户端 API 端口 | 重启 |
| `rover.nameserver.manageBindHost` | `0.0.0.0` | HTTP 监听地址 | 重启 |
| `rover.nameserver.token` | 空 | 注册/订阅协议 token；空=不鉴权（启动 WARN）。正式机建议非空或开严格安全 | 重启 |
| `rover.nameserver.adminToken` | 空 | `/_manage/**` 管理 token；同上 | 重启 |
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

Nameserver 的 `managePort: 0` 仅关闭 HTTP 管理监听；当前不会自动忽略已有的
`config/nameserver-runtime.overlay.json`。若希望 YAML 重新成为配置来源，请先确认或移除相应的 overlay 值。
