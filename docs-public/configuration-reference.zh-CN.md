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
| `rover.admin.admin-token` | 空 | Admin 调用下游时发送的 `X-Rover-Admin-Token`；与登录鉴权无关 | 重启 |
| 控制台账号 | `admin` / `admin` | 存在记录库同一份 H2 的 `admin_user` 表。表为空时写入这一对默认账号；已有记录不覆盖。多人共用这一个账号 | 首次启动写入 |
| `rover.admin.log-store-path` | `./rover-logs/rover` | 本地 H2 记录库位置（实际文件 `<路径>.mv.db`），目录缺失自动创建；存放 Agent 后续通过 `queryLogs` 回读的运行证据 | 重启 |
| `rover.admin.log-retention-days` | `30` | 诊断证据类保留天数（配置变更 / 回滚 / 错误 / 实例健康翻转），超期清理 | 重启 |
| `rover.admin.log-telemetry-retention-days` | `3` | 遥测类保留天数（指标采样 / 慢与 5xx 链路）：量大，短保留防膨胀 | 重启 |
| `rover.admin.log-collect-interval-seconds` | `30` | 遥测采集间隔（秒）：多久拉一轮 Gateway / Nameserver 的状态、指标、实例与链路写进记录库（下限 5，首轮延迟一个周期） | 重启 |
| `rover.admin.log-queue-capacity` | `8192` | 普通（遥测）队列容量：best-effort，满了直接丢弃，绝不让业务请求等待 | 重启 |
| `rover.admin.log-critical-capacity` | `16384` | 高优（诊断证据）队列容量：满了短暂等待，尽量不丢 | 重启 |
| `rover.admin.model.config-file` | 空 = 工作目录下 `config/admin-model.properties` | 模型配置文件路径（UTF-8 properties，含 `api-key-enc` 密文）；文件缺失时启动自动创建空配置，整个 `config/` 已在 `.gitignore` 中；环境变量 `ROVER_ADMIN_MODEL_CONFIG_FILE` | 重启 |
| `rover.admin.model.master-key` | 空 | base64 解出 32 字节则直接用作 AES 密钥，否则按口令 PBKDF2-HMAC-SHA256（65536 轮）派生；环境变量 `ROVER_ADMIN_MASTER_KEY` | 重启 |
| `rover.admin.model.master-key-file` | 空 = 配置文件同级 `master.key` | 主密钥文件；首次加密时自动生成 32 字节随机主密钥并落盘；环境变量 `ROVER_ADMIN_MASTER_KEY_FILE` | 重启 |
| `server.servlet.session.timeout` | `30m` | 控制台会话超时 | 重启 |
| `server.servlet.session.cookie.name` | `ROVERADMIN_SESSION` | 会话 Cookie 名 | 重启 |
| `server.servlet.session.cookie.http-only` | `true` | 会话 Cookie 仅 HTTP 可用 | 重启 |
| `server.servlet.session.cookie.same-site` | `Strict` | 会话 Cookie 的 SameSite 策略 | 重启 |

`rover.admin.admin-token` 只在 Admin 调用 Gateway/Nameserver 时作为 `X-Rover-Admin-Token` 发送，与登录鉴权无关。

控制台页面与全部 `/api/*` 必须先登录：未登录时页面 302 跳 `/login.html`，`/api/*` 回 `401`。
账号在记录库的 `admin_user` 表，默认用户名和口令都是 `admin`，多人共用这一个账号。会话 Cookie 为
`ROVERADMIN_SESSION`（HttpOnly、SameSite=Strict），超时取 `server.servlet.session.timeout`。

### 主密钥隔离与启动告警

`api-key-enc` 的密文强度取决于主密钥是否与配置分开保存：默认 `master.key` 与 `admin-model.properties` 同目录，
拷走目录就能连密文一起解密，等同于未加密。此时启动打一条 `[SECURITY WARNING]`（不阻断启动，也不取消自动生成机制）。
生产环境二选一即可：设置环境变量 `ROVER_ADMIN_MASTER_KEY`（或 `rover.admin.model.master-key`），
或用 `rover.admin.model.master-key-file` 把主密钥指到独立目录/独立挂载卷。密钥、明文口令与密文都不会进日志或响应。

### 落盘记录库（`rover.admin.log-*`）

上面六个键对应 Admin 进程内的本地 H2 记录库：配置变更 / 回滚 / 写失败、组件与实例健康翻转、按周期聚合的指标采样、
慢与 5xx 链路都写在这里，与有没有人提问无关。写入是异步双队列（诊断证据优先、遥测 best-effort），
清理任务每 60 分钟按上面的保留期各清一类。**它是本机数据**（`rover-logs/` 已在 `.gitignore` 中），
换机或清目录即丢，多副本 Admin 时各写各的，不能当作跨机审计源。它写入的内容与 Agent 怎么读它，
见 [Rover Ops Agent §6.3](./ops-agent.zh-CN.md#63-运行证据落库与遥测采集)。

## Agent Workbench 配置

| 配置 | 默认值 | 说明 | 生效方式 |
| --- | --- | --- | --- |
| `rover.agent.execution.worker-threads` | `2` | 调查工作线程数（1~32）；模型与只读端口的阻塞调用只占这些线程，不阻塞 Servlet 请求线程 | 重启 |
| `rover.agent.execution.queue-capacity` | `16` | 执行队列容量（1~1000）；队列满时提交回 `429`，不使用无界队列 | 重启 |
| `rover.agent.execution.task-capacity` | `200` | 任务登记容量（1~10000）；已结束的任务可被保留策略淘汰，执行中的任务不会被丢 | 重启 |
| `rover.agent.context.recent-message-limit` | `8` | 追问时带上的最近消息条数 | 重启 |
| `rover.agent.planning.max-rounds` | `3` | 单次动态调查最多规划轮数（>0）；触顶后停止采集并如实记录判断边界 | 重启 |
| `rover.agent.planning.max-tool-calls` | `10` | 单次调查最多只读能力调用次数（>0）；触顶后不再发起任何调用，未执行的步骤如实上报 | 重启 |
| `rover.agent.planning.max-plan-steps` | `6` | 单轮计划最多步骤数（>0）；超出部分由计划校验截断，未登记的写能力一律被丢弃 | 重启 |
| `rover.agent.llm.quick-timeout-seconds` | `10` | 目标解析 / 调查规划这类"失败也能兜底"的小调用等待上限（秒，`0`=用模型配置里的超时）；只在超时后重试一次，「AI 解读」与对话主路径不受影响 | 重启 |
| `rover.agent.metrics.enabled` | `true` | Agent 运行指标总开关；`false` 为应急降级，不注册任何 Meter，业务逻辑不变 | 重启 |
| `management.metrics.export.prometheus.enabled` | `false` | 指标对外出口开关：置 `true` 后注册表切换为 `PrometheusMeterRegistry`，由 `/actuator/prometheus` 供抓取；指标名与标签口径不变（`rover.agent.*`）。需同步在 `management.endpoints.web.exposure.include` 中加入 `prometheus` | 重启 |
| `spring.ai.tools.limits.max-total-tool-calls` | `30` | 对话主路径单次任务的工具调用总预算，需与 `OpsTools.MAX_CALLS` 对齐；给小了会让「一句话问三件事」在取数途中被掐断 | 重启 |
| `spring.ai.tools.limits.max-calls-per-tool-default` | `10` | 该预算内单个工具的调用上限 | 重启 |
| `spring.ai.openai.fast-base-url` / `fast-model` / `fast-api-key` | 空 | 可选的小模型，承接目标解析 / 调查规划这类廉价结构化调用；未配置时回落主模型并关闭 thinking | 重启 |

三个执行参数与三个规划限制越界时启动直接失败，让配置错误在启动期暴露，而不是运行期表现为「任务莫名被拒」或「调查提前收尾」。

指标口径（前缀 `rover.agent.`，写在宿主进程的同一个 `MeterRegistry` 上）：`task.submitted` / `task.completed` /
`task.failed` / `task.rejected` 为计数，`task.active` / `task.queue.size` / `sse.connections` 为当前值；
`task.duration`（标签 `status`）、`model.duration`（标签 `model`、`scene`）、`model.calls`（标签 `model`、`scene`、`outcome`）
与 `model.tokens`（标签 `model`、`scene`、`kind`，取值 `prompt` / `completion`；拿不到用量就不上报、不记 0）分别记录耗时、
调用次数与 token 用量。原 `model.error` 已移除：单一 failed 布尔只能回答「失败几次」，回答不了「失败在哪一环」，
现由 `model.calls` 的 `outcome` 标签（`ok` / `not_configured` / `unavailable` / `timeout` / `error` / `empty` / `rejected`）区分失败原因，
其中 `rejected` 表示「模型返回了文本但不符合输出契约」。`scene` 取有限中文取值（`目标解析`、`调查规划`、`解读`、`对话`），
空白归一为 `unknown`，超 24 字符截断。
标签只允许 `status`、`reason`、`model`、`scene`、`outcome`、`kind` 六种有限取值，模型名会规范化并截断，不引入标签基数风险。

## Gateway 启动配置

| 配置 | 默认值 | 说明 | 生效方式 |
| --- | --- | --- | --- |
| `rover.gateway.port` | `80` | 业务 HTTP 端口 | 重启 |
| `rover.gateway.adminEnabled` | `true` | 是否启用 `/_manage/**`、Admin 路由 overlay 与运行时配置 overlay | 重启 |
| `rover.gateway.adminToken` | 空 | `/_manage/**` 管理口 token；空=不鉴权（启动 WARN）。正式机建议非空，或设 `ROVER_STRICT_SECURITY=true` | 重启 |
| `rover.gateway.server.bindHost` | `0.0.0.0` | 业务监听地址 | 重启 |
| `rover.gateway.server.maxContentLengthBytes` | `1048576` | 请求体上限（入站管道计数，超限回 413 并关掉这根连接；已经转到上游的立刻拆掉，不等请求超时）。不再经过 `HttpObjectAggregator` | 重启 |
| `rover.gateway.server.dispatchOnEventLoop` | `false` | 业务 Handler 是否留在 EventLoop。默认走业务线程池，和收发包分开。确认无阻塞才改 `true` | 重启；也可用 `-Drover.gateway.dispatchOnEventLoop` |
| `rover.gateway.server.ioTransport` | `auto` | 入站 EventLoop 与出站 Channel 用同一套 I/O。`auto`：Linux 选 Epoll（Docker Linux 容器里一般是这个），macOS 上直接运行进程选 KQueue，原生库不可用时退 NIO。也可写死 `nio` / `epoll` / `kqueue`（不可用时退 NIO） | 重启；也可用 `-Drover.gateway.ioTransport` |
| `rover.gateway.server.maxInflight` | `max(64, CPU×8)` | 整机在途闸门。不写或 `0` 用默认。超限 503，原因头 `INFLIGHT_LIMIT` | 重启；也可用 `-Drover.gateway.maxInflight`。YAML 正数优先 |
| `rover.gateway.server.idleTimeoutSeconds` | `60` | 入站 Keep-Alive 读空闲超时。闲着且这根连接上没有正在处理的请求才关掉，不回 408。不写或 `0` 用默认 | 重启；也可用 `-Drover.gateway.server.idleTimeoutSeconds` |
| `rover.gateway.server.requestIdleTimeoutSeconds` | `30` | 请求还没收齐时，客户端多久不送字节就关连接（顺带拆上游）。每个 chunk 会重置计时。body 收齐后改等 `proxy.requestTimeoutMillis`。不写或 `0` 用默认 | 重启；也可用 `-Drover.gateway.server.requestIdleTimeoutSeconds` |
| `rover.gateway.metrics.enabled` | `true` | 启动时指标总开关；`false` 热路径不记 inflight/record。503 拒绝计数仍记 | 重启；Admin 关闭时只认 YAML |
| `rover.gateway.metrics.windowSeconds` | `300` | 指标滑动窗口（秒） | 重启 / 运行时 |
| `rover.gateway.trace.enabled` | `true` | 启动时时间线开关；`false` 不 `markPhase`、不造 traceId | 重启；Admin 关闭时只认 YAML |
| `rover.gateway.trace.slowThresholdMillis` | `100` | 慢请求阈值 | 重启 / 运行时 |
| `rover.gateway.trace.sampleRate` | `0.0` | 采样率 0~1 | 重启 / 运行时 |
| `rover.gateway.proxy.outbound` | `netty` | 出站客户端。默认 `netty` 只转发 `http://` 上游。`jdk` 是第一版 JDK `HttpClient`（强制 HTTP/1.1），主要用于回滚和对照；它自带 TLS，因此上游必须是 `https://` 时可以切过去。也可用 `-Drover.gateway.proxy.outbound` | 重启 |
| `rover.gateway.proxy.connectTimeoutMillis` | `3000` | 上游连接超时。不写或 `0` 用默认 | 重启 |
| `rover.gateway.proxy.requestTimeoutMillis` | `30000` | 上游请求超时的启动默认值。不写或 `0` 用默认；运行时还可改 `gateway.request.timeoutMillis` | 重启 |
| `rover.gateway.proxy.maxConnectionsPerEventLoop` | `64` | 每个 EventLoop、每个后端 `host:port` 各一个连接池，池内最多这么多条。不写或 `0` 用默认。总连接大约是该值 × worker 数（worker 默认约 CPU×2） | 重启；也可用 `-Drover.gateway.proxy.maxConnectionsPerEventLoop` |
| `rover.gateway.proxy.maxPendingAcquires` | `256` | 单个池满了以后最多排队等连接的请求数。不写或 `0` 用默认 | 重启；也可用 `-Drover.gateway.proxy.maxPendingAcquires` |
| `rover.gateway.proxy.idleTimeoutSeconds` | `60` | 出站池闲连接读空闲超时。还在转发（等上游）时不踢。不写或 `0` 用默认 | 重启；也可用 `-Drover.gateway.proxy.idleTimeoutSeconds` |
| `rover.gateway.discovery.type` | `STATIC`（代码默认）/ 示例为 `nameserver` | `static`、`nameserver` 或 `nacos` | 重启 |
| `rover.gateway.discovery.nameserver.address` | `127.0.0.1:8888` | Nameserver TCP 地址 | 重启 |
| `rover.gateway.discovery.nameserver.reconcileIntervalMs` | `30000` | 本地实例缓存周期对账间隔 | 重启 |
| `rover.gateway.discovery.nameserver.token` | 空 | Gateway 访问 Nameserver 的协议 token | 重启 |
| `rover.gateway.discovery.nacos.serverAddr` | `127.0.0.1:8848` | Nacos 地址；`discovery.type=nacos` 时必填。需 `-Pnacos` 或自行加入 adapter | 重启 |
| `rover.gateway.discovery.nacos.namespace` | 空 | 命名空间 ID。public 请留空，不要填 `public` | 重启 |
| `rover.gateway.discovery.nacos.username` | 空 | Nacos 登录用户名；未开鉴权留空 | 重启 |
| `rover.gateway.discovery.nacos.password` | 空 | Nacos 登录密码；未开鉴权留空 | 重启 |
| `rover.gateway.discovery.nacos.timeoutMs` | `3000` | Naming 请求超时（毫秒） | 重启 |
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
| `rover.gateway.rewrite.stripPrefix` | 空 | 全局重写前缀；建议留空，改写写在每条路由上，避免新路由继承 `/api` | 重启 |
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
YAML `server.maxInflight` 或 `-Drover.gateway.maxInflight` 覆盖）、`NO_UPSTREAM`（没有可用上游）或 `CIRCUIT_OPEN`（候选实例都被熔断打开）。日志里会打 `inflight=已用/上限`。
`/_manage/metrics` 的 `resources.rejects` 和 Prometheus `rover_gateway_rejects_total` 按原因计数。
这些只发生在拒绝路径，成功请求不加活。

### 路由字段

`rover.gateway.routes` 是路由数组，不是单值配置。每项支持：`id`、`businessPrefix`、`targetUrl`、
`targetUrls`、`targets`、`stickyHeader`、`stripPrefix`。

一条路由的上游二选一：

- **静态** —— `targetUrl` 或 `targetUrls`，元素可用 `http://host:port|weight` 指定权重；
- **动态 / 版本化** —— `targets`，元素为 `{serviceName, group, weight}`。

两者**互斥**，同一条上都写会被校验拒绝。旧的扁平 `serviceName` / `group` 字段已删除。整机
`discovery.type` 是 `nameserver`/`nacos` 时，个别路由仍可只写静态地址，只要这条路由只用静态地址。

版本目标按「同一服务的多个版本」收窄校验：

- 每个 target 的 `serviceName` 非空；
- 同一条路由的所有 target 必须**同一个** `serviceName`——这条路由不是通用的多服务聚合，
  这样按版本看指标、按版本回滚才有单一语义；
- `weight ∈ 0~10000`；`weight: 0` 表示「注册但不接流」，这是暂停一个版本而不删掉它的方式；
- `(serviceName, group)` 不重复；
- 权重之和必须**大于 0**。

`group` 是注册中心的通用业务分组，灰度路由只是**复用它作为版本维度**（`v1` / `v2` / …），因此没有单独的版本
字段。可选的 `stickyHeader` 指定粘性路由用的请求头，必须是合法的 HTTP 头名 `[A-Za-z0-9-]+`；
不写则回退客户端 IP，两者都为空时按权重路由。

95/5 灰度示例：

```yaml
routes:
  - id: order-api
    businessPrefix: /api/orders
    targets:
      - serviceName: order-service
        group: v1
        weight: 95
      - serviceName: order-service
        group: v2
        weight: 5
    stripPrefix: /api
```

把 `v2` 从 `5` 调到 `20`（`v1` 从 `95` 调到 `80`）时，原先落 `v2` 的键一个都不会被打回 `v1`：
权重区间只向左侧扩张。分流算法与管理流程见 [Gateway 版本化灰度发布](./gateway-gray-release.zh-CN.md)。

默认 `outbound=netty` 时上游必须是 `http://`。写入 `https://` 会在启动或热更新时被拒绝，避免配置通过、
请求才失败；若上游确实是 HTTPS，先把 `proxy.outbound` 设为 `jdk` 再重启。Admin 保存的路由会写入
`config/routes.overlay.json`（文件同时带 `revision` 与 `appliedOperationId`），并整体替换启动 YAML 中的
路由列表。Nacos 整机示例见 `deploy/docker/config/rover-gateway-nacos.yml`。

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
