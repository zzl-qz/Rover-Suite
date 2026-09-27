# Rover-Admin API

Admin API 默认与控制台同源，地址为 `http://127.0.0.1:9090`，所有接口前缀为 `/api`。Admin 本身是静态控制台加聚合层，默认不保存业务数据；它会调用 Gateway 和 Nameserver 的管理接口。

控制台页面与 `/api/*` 由 Spring Security 保护：未登录时页面跳 `/login.html`，接口回 `401`。只有配置了
`rover.admin.auth.password-hash`（BCrypt，推荐）或 `rover.admin.auth.password`（明文，启动期哈希进内存）才启用登录；
两者都留空时不鉴权（仅限本机调试，启动打 WARN 且页面顶栏显示提示条）。

`rover.admin.admin-token` 与登录鉴权是两回事：它只用于 Admin 调用下游组件时发送 `X-Rover-Admin-Token`。
生产环境建议同时启用登录，并用绑定地址、防火墙、反向代理或 VPN 限制 9090 的访问来源。

## 接口目录

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/overview` | Gateway/Nameserver 状态、发现模式和指标自洽检查 |
| GET | `/api/live?range=60\|300` | 轻量实时快照，供仪表盘轮询 |
| GET | `/api/routes` | Gateway 路由列表 |
| POST | `/api/routes` | 新增或更新路由，请求体为路由对象 |
| DELETE | `/api/routes?businessPrefix=/api/demo` | 按业务前缀删除路由 |
| GET | `/api/instances` | Nameserver 注册实例列表 |
| GET | `/api/nameserver/metrics` | Nameserver 指标快照 |
| GET | `/api/events` | Nameserver 最近事件 |
| GET | `/api/traces?traceId=&path=&slow=` | Gateway 请求链路记录及筛选 |
| GET | `/api/configs` | Gateway/Nameserver 配置元数据 |
| POST | `/api/configs` | 更新一个配置项 |
| GET | `/api/agent/sessions` | 当前用户的会话列表 |
| POST | `/api/agent/sessions` | 新建会话；请求体 `{"title":"..."}`，标题可选 |
| GET | `/api/agent/sessions/{sessionId}/workspace` | 工作台聚合视图：会话 + 对话 + 事件 + 最近任务（`limit` 默认 20，上限 100） |
| POST | `/api/agent/sessions/{sessionId}/messages` | 发一条消息（新问题或对当前事件的追问），`202` 返回任务句柄 |
| GET | `/api/agent/tasks/{taskId}` | 任务详情：步骤、证据与结论 |
| GET | `/api/agent/tasks/{taskId}/events` | 任务事件流（SSE）：先补发 `SNAPSHOT`，再按增量推送结构化事件 |
| GET | `/api/auth/status` | 登录态与 CSRF 令牌；免登录 |
| POST | `/login` | 表单登录（`username`、`password`、`_csrf`）；成功 302 到 `redirect` 或 `/`，失败 302 到 `/login.html?error=1` |
| POST | `/api/logout` | 登出，成功后回 200；只认 POST |
| GET | `/api/model/config` | 当前模型配置、生效状态与预设列表 |
| POST | `/api/model/config` | 保存模型配置并立即生效（无需重启 Admin） |
| POST | `/api/model/test` | 用请求体里的候选值做连接测试，不落盘 |
| POST | `/api/model/verify` | 对当前已生效的配置做效果验证 |

Agent 任务状态为 `PENDING`、`RUNNING`、`WAITING_INPUT`、`COMPLETED` 或 `FAILED`（另有协议预留的 `CANCELLED`）。
`WAITING_INPUT` 表示目标无法从问题与会话上下文确定，任务停在澄清点等待用户补充，不算执行中，也不占用会话的并发位；
澄清提问在任务的 `clarification` 字段里，用户补充信息后由会话的下一次任务继续。结果包含 `summary`、`confidence`、
带来源和采集时间的 `evidence`、`limitations`，以及假设验证 `hypotheses`：每条含 `id`、`statement`、
`status`、`detail`、`sources`，其中 `status` 为 `CONFIRMED`、`REJECTED` 或 `UNKNOWN`，分别表示该假设
被确认、排除或证据不足无法验证。配置模型后还会包含可选的 `aiAnalysis`。诊断全程只读，不会修改路由或配置；
任务存于 Admin 内存，重启后不可查询。

每条证据除 `source`、`observedAtMillis`、`rawReference` 外，`metadata` 里带统计口径：`windowSeconds`（窗口秒数，`0` 表示时点快照）、
`sampleSize`（样本量），以及该证据的资源维度（`routeId` / `hostPort` / `component`）。样本不足时如实写「无法判断」，
不用空样本冒充「已恢复」。故障调查的假设集包含「该路由的某个上游实例返回了 5xx」：只有窗口内该实例样本达到阈值（5 条）
且确有 5xx 才判 `CONFIRMED` 并点名实例；样本达标且全部非 5xx 判 `REJECTED`；窗口内没有任何转发记录时判 `UNKNOWN`，
并明确说明「不能据此认定异常已恢复」。

P2 起任务快照不再只有「故障调查」一种形态，`GET /api/agent/tasks/{taskId}` 与 SSE 快照都带以下字段：
`taskType`（`QUERY` / `INVESTIGATION` / `EXPLAIN` / `ACTION_PLAN` / `UNSUPPORTED`）、`intent`（`intent`、`confidence`、
`reason`、`targetHint`、`requestedAction`）、`plan`（`goal`、`hypotheses`、`steps`；每步含 `capability`、`reason`、
`target`、`required`）、`executedCapabilities`（实际调用或按事实跳过的只读能力）与 `actionPlan`（处置计划）。
同一句话按意图走不同形态：状态查询只调用一个只读能力、不建事件；能力咨询由能力注册表回答、不进入目标解析；
处置请求只产出可人工审核的计划、不执行写操作；定时巡检等未开放请求如实说明边界、不跑空调查。
处置计划含 `actionType`、`riskLevel`、`currentState`、`desiredState`、`expectedImpact`、`verificationPlan`、
`rollbackPlan`、`blockedReason`；`executable` 恒为 `false`，`blockedReason` 固定说明「当前版本仅生成计划，不执行」。

任务事件流 `GET /api/agent/tasks/{taskId}/events` 用 `text/event-stream` 推送该任务的全部结构化事件：SSE 事件名即事件类型，
数据是完整的事件信封（`eventId`、`taskId`、`type`、`timestampMillis`、`payload`）。建连时先补发一份 `SNAPSHOT`
（任务视图 + 已产生的解读文本 + 已覆盖的事件序号 `coveredEventId`），随后按增量推送 `TASK_STARTED`、`STEP_STARTED`、
`STEP_COMPLETED`、`STEP_FAILED`、`EVIDENCE_ADDED`、`ANALYSIS_DELTA`、`CLARIFICATION_REQUIRED` 等；
任务进入终态（`TASK_COMPLETED`/`TASK_FAILED`/`TASK_CANCELLED`）或停在澄清点时收尾并关闭连接。
意图判定、调查计划、能力执行进展与处置计划不是过程日志，而是结果的一部分：它们随 `SNAPSHOT` 全量快照推送（覆盖语义），
中途接入或断线重连的客户端都能还原「识别成什么意图、计划查什么、实际查了什么、处置计划是什么」。
事实来源始终是 `GET /api/agent/tasks/{taskId}`：任何事件表达的信息都必须能查到，丢事件、断连接都不影响任务继续执行。
连接空闲超时 180 秒，超时或断线后前端重连并靠补发快照对齐，不会丢内容。任务不存在、已不可观察或不属于当前用户时回 `404`。

## Agent Workbench 接口

### 会话与消息

- `POST /api/agent/sessions`：新建会话，返回 `Session`（`sessionId`、`userId`、`title`、`activeIncidentId`、`status`、时间戳、`incidentIds`）。标题留空时由第一句提问推导。
- `GET /api/agent/sessions`：返回**当前用户**的会话列表（按创建顺序）。
- `POST /api/agent/sessions/{sessionId}/messages`：请求体只有 `message` 必填：

| 字段 | 说明 |
| --- | --- |
| `message` | 用户这一轮的话，必填；支持「为什么 /api/demo/tt 调用失败？」这类自然语言 |
| `path` / `service` / `instance` | 可选的「高级上下文」手工指定项，三者互斥，优先级 path > service > instance |
| `fromMillis` / `toMillis` | 可选时间范围（毫秒时间戳）；要么都不填，要么填成合法闭区间，否则回 `400` |

响应为 `202 Accepted` + `{"sessionId":...,"taskId":...,"status":"PENDING"}`：提交只做校验与登记，目标解析与调查执行
都在后台 worker 里进行，因此本接口不会被模型调用阻塞。进度用 `GET /api/agent/tasks/{taskId}` 与任务事件流观察。

- 同一会话已有执行中的任务时回 `409` + `{"code":"SESSION_TASK_RUNNING","message":...,"taskId":"<在跑的任务>"}`：
  一个会话同时只允许一个主动调查任务在跑。
- 任务容量或执行队列已满时回 `429` + `{"code":"TASK_BUSY","message":"调查任务繁忙，请稍后再试"}`。
- 目标无法确定时不猜、也不另开事件：任务停在 `WAITING_INPUT`，`clarification` 是要补充的问题，
  同时该提问作为一条 Agent 消息写入会话；用户回复后由会话的下一次任务继续（本阶段不做同一任务的原地恢复）。
  **只有故障调查才会因目标不明而澄清**：能力咨询与全局指标查询不要求资源目标。

支持的问法与落点（都可直接用一句自然语言触发）：

| 问法示例 | 落点 |
| --- | --- |
| `网关 QPS 多少？` | 状态查询：只调 `GATEWAY_METRICS_QUERY`，回答窗口请求数与 5xx 计数，不建事件、不跑调查 |
| `order-service 几个健康实例？` | 状态查询：只调 `INSTANCE_QUERY`，按解析出的服务统计健康实例数 |
| `/api/demo/tt 命中了哪条路由？` | 状态查询：只调 `ROUTE_QUERY`，回答命中的路由事实 |
| `为什么 /api/demo/tt 调用失败？` | 故障调查：按动态计划（路由 → 实例 → 指标 → 追踪）执行只读调查，产出假设验证结论 |
| `你能做什么？` | 解释：由 `CapabilityRegistry` 列出真实能力清单（路由 / 实例 / 网关指标 / 追踪 / 配置 / 注册事件），不解析资源；`你是谁` / `介绍一下你自己` 同义 |
| `你好` / 看不出意图的闲聊 | 兜底：不追问资源路径，回一段自我介绍 + 能力清单（同一份注册表），告诉用户可以怎么问 |
| `把 order-03 摘掉` | 处置计划：只读预检 + 不可执行的 `ActionPlan`，`executable=false` |
| `每天 9 点自动巡检并发邮件` | 未开放：如实说明「定时巡检尚未开放」，不进入调查 |

`planning`（轮数 / 能力调用次数 / 单轮步数）上限见 `configuration-reference`；触顶时任务会把限制写进
`limitations` 并如实上报未执行的步骤，不会假装查完。

### 工作台聚合视图

`GET /api/agent/sessions/{sessionId}/workspace?limit=20` 一次返回
`{"session":...,"messages":[...],"incidents":[...],"tasks":[...],"activeIncident":...}`，省掉前端为每个任务各发一次请求。
`limit` 只约束任务条数（默认 20，上限 100），`tasks` 按创建时间倒序；完整任务详情仍由 `GET /api/agent/tasks/{taskId}` 按需取。
会话不属于当前用户时回 `404`。

### 上下文与追问

同一个会话里的后续消息不是独立问题：编排层会带上**最近 N 条消息**（`rover.agent.context.recent-message-limit` 配置，
默认 8）+ **当前事件上下文** + **当前结构化调查对象** + **该事件的关键证据**，因此「为什么没有实例？」「那昨天呢？」
「只看刚才那个服务」这类追问能落在同一事件上。事件复用规则是**目标一致就沿用当前事件**（本轮显式给出时间范围时，
顺带更新该事件的时间范围）；只有解析出明确的不同对象时才另开事件并把它设为当前事件。解析不出新对象时，
沿用当前事件的目标属于会话连续性，不算猜测；确实没有可继承目标时任务停在 `WAITING_INPUT` 等用户补充信息，
这一轮不产生事件、也不发起调查。
事件上的 `summary` 与 `status` 是「最近一轮结论」的聚合口径：有新调查挂上来即转 `INVESTIGATING`，出结论即转 `RESOLVED` 并更新摘要。

`message` 为空或超过 1000 字回 `400`。`GET /api/agent/tasks/{taskId}` 只返回属于当前用户的记录，否则回 `404`。

### 用户身份

`userId` 一律取自后端认证上下文（`Authentication.getName()`），未启用登录或匿名访问时为空；请求体里没有也不接受
`userId` 字段，前端提交同名 JSON 字段会被忽略。会话、任务与事件的查询都按该身份过滤，默认用户只能看到自己的记录。

### 调用示例

```bash
# 新建会话
sessionId=$(curl -s -b "$jar" -H "X-XSRF-TOKEN: $token" -H "Content-Type: application/json" \
  -d '{}' http://127.0.0.1:9090/api/agent/sessions | sed -E 's/.*"sessionId":"([^"]*)".*/\1/')

# 提问（自然语言）；202 返回 {"sessionId":...,"taskId":...,"status":"PENDING"}
taskId=$(curl -s -b "$jar" -H "X-XSRF-TOKEN: $token" -H "Content-Type: application/json" \
  -d '{"message":"为什么 /api/demo/tt 调用失败？"}' \
  "http://127.0.0.1:9090/api/agent/sessions/$sessionId/messages" | sed -E 's/.*"taskId":"([^"]*)".*/\1/')

# 追问（沿用当前事件，可手工指定时间范围）
curl -s -b "$jar" -H "X-XSRF-TOKEN: $token" -H "Content-Type: application/json" \
  -d '{"message":"那昨天呢？","fromMillis":1735689600000,"toMillis":1735776000000}' \
  "http://127.0.0.1:9090/api/agent/sessions/$sessionId/messages"

# 工作台聚合 / 任务详情 / 任务事件流
curl -s -b "$jar" "http://127.0.0.1:9090/api/agent/sessions/$sessionId/workspace"
curl -s -b "$jar" "http://127.0.0.1:9090/api/agent/tasks/<taskId>"
curl -N -b "$jar" "http://127.0.0.1:9090/api/agent/tasks/<taskId>/events"
```

## 请求示例

读取仪表盘的 1 分钟窗口：

```bash
curl "http://127.0.0.1:9090/api/live?range=60"
```

新增或更新路由：

```bash
curl -X POST "http://127.0.0.1:9090/api/routes" \
  -H "Content-Type: application/json" \
  -d '{"id":"demo-api","businessPrefix":"/api","serviceName":"demo-service"}'
```

更新配置：

```bash
curl -X POST "http://127.0.0.1:9090/api/configs" \
  -H "Content-Type: application/json" \
  -d '{"component":"gateway","key":"gateway.trace.sampleRate","value":"1"}'
```

路由请求体字段应与 Gateway 的路由模型一致；配置更新的 `component` 只能是 `gateway` 或 `nameserver`，`key` 和 `value` 会由下游组件再次校验。`gateway.loadbalance.strategy` 属于启动/插件装配配置，不通过 Admin API 修改。

## 鉴权、会话与 CSRF

- 免登录路径：`/login.html`、`POST /login`、`/api/auth/status`，以及静态资源 `/css/**`、`/js/**`、`/vendor/**`、`/images/**`、`/favicon.svg`、`/error`。
- 未登录时的差别：`/api/*` 回 `401` 与 `{"code":401,"message":"请先登录控制台"}`；页面请求 302 到 `/login.html`。
- 写请求（POST/PUT/DELETE）必须带 CSRF 头 `X-XSRF-TOKEN`，令牌取自 `XSRF-TOKEN` cookie（`GET /api/auth/status` 会顺手把 cookie 铺上，也可直接读该接口的 `csrfToken` 字段）；缺失或不匹配回 `403`。
- 登录成功后 Spring Security 会**轮换** CSRF 令牌，因此调用方每次写请求都应从 cookie 现读令牌，不要缓存。
- 同一来源在 `rover.admin.auth.failure-window-seconds` 窗口内连续失败达到 `rover.admin.auth.max-login-failures` 次后，`POST /login` 直接回 `429`；登录成功会清空该来源的计数。失败与成功都不区分"用户不存在/口令错"，失败统一 302 到 `/login.html?error=1`。
- 会话 cookie 名为 `ROVERADMIN_SESSION`（HttpOnly、SameSite=Strict），超时取 `server.servlet.session.timeout`（默认 30 分钟，以 `GET /api/auth/status` 的 `sessionTimeoutSeconds` 为准）；登录成功会更换 session id。
- 登出只认 `POST /api/logout`：GET 登出无法被 CSRF 保护，恶意页面可以借浏览器把操作者踢下线。
- SSE 接口 `GET /api/agent/tasks/{taskId}/events` 未登录同样回 `401`。浏览器 `EventSource` 无法附加自定义请求头，
  它只靠会话 Cookie 鉴权；该接口是 GET，不需要 CSRF 头，登出后连接随会话失效。

带登录态的脚本调用：

```bash
jar=$(mktemp)
token=$(curl -s -c "$jar" http://127.0.0.1:9090/api/auth/status | sed -E 's/.*"csrfToken":"([^"]*)".*/\1/')
curl -s -b "$jar" -c "$jar" -o /dev/null -w '%{http_code}\n' \
  -d "username=admin&password=你的口令&_csrf=$token" http://127.0.0.1:9090/login
# 登录后令牌被轮换，写请求前重新读一次
token=$(curl -s -b "$jar" -c "$jar" http://127.0.0.1:9090/api/auth/status | sed -E 's/.*"csrfToken":"([^"]*)".*/\1/')
curl -s -b "$jar" -H "X-XSRF-TOKEN: $token" http://127.0.0.1:9090/api/model/config
```

订阅某任务的事件流（`-N` 关闭 curl 缓冲，才能看到分块实时到达；未登录时这里会直接回 `401`）：

```bash
curl -N -b "$jar" "http://127.0.0.1:9090/api/agent/tasks/<taskId>/events"
```

## 模型配置接口

`GET /api/model/config` 返回当前配置、生效状态与预设：

| 字段 | 说明 |
| --- | --- |
| `enabled` / `baseUrl` / `model` / `timeoutSeconds` | 当前保存的配置值；超时收敛在 1~300 秒 |
| `source` | `FILE`（页面保存过）、`ENV`（环境变量播种，尚无配置文件）、`NONE`（未配置） |
| `configured` | 启用且地址与模型名都不为空，表示"配过" |
| `available` | 已构建出可用的模型客户端，表示"当前可用"；与 `configured` 是两件事 |
| `applied` | 已生效的配置与当前保存的配置是否一致；`false` 表示需重新保存 |
| `description` / `buildId` / `appliedAt` / `lastError` | 生效模型描述、生效版本号、生效时间、最近一次错误 |
| `apiKeyMasked` | 只可能是 `******` 或空串；明文与密文都不会出现在任何响应里 |
| `apiKeyReadable` / `masterKeyState` | 密钥能否解密；`masterKeyState` 为 `MISMATCH` 时需要在页面上重新填写密钥 |
| `configFile` | 配置文件绝对路径，便于备份与排障 |
| `presets` | 内置预设（OpenAI、DeepSeek、阿里云百炼、智谱、本地 Ollama、本地 vLLM），页面用它填充下拉 |

`POST /api/model/config` 的请求体字段与上表同名：

- 不带 `apiKey` 字段、或传 `******`：保留已存密钥；只有 `"clearApiKey":true` 才清除（本地无鉴权模型用）。
- `enabled=false` 只停用模型能力，配置仍保留；启用时 `baseUrl` 与 `model` 都不能为空，地址须以 `http://` 或 `https://` 开头。
- 保存成功后新客户端立即生效，同时返回 `message`（"模型配置已生效，无需重启 Admin"或"已保存，但当前不可用"）。

`POST /api/model/test` 与 `POST /api/model/verify` 都返回 `ok`、`latencyMs`（毫秒，失败为 -1）、`errorCode`、
`message`、`model`、`baseUrlHost`，verify 另带 `buildId` 与 `appliedAt`。`errorCode` 取值：`AUTH`、`PERMISSION`、
`QUOTA`、`MODEL_NOT_FOUND`、`BAD_REQUEST`、`NETWORK`、`SERVER`、`TIMEOUT`、`UNKNOWN`，以及 verify 专用的
`NOT_CONFIGURED`、`UNAVAILABLE`。两者区别：test 用请求体里的候选值探活、不落盘、不影响当前生效配置；verify
对当前已生效的配置再跑一次，用来证明"生效的正是这次保存的配置"。

## 响应与错误

- 成功响应为 JSON。配置更新至少返回 `component`、`key` 和 `message`，部分配置会附带 `payload`。
- 未登录访问 `/api/*` 回 `401` + `{"code":401,"message":"请先登录控制台"}`；写请求缺 CSRF 头回 `403`；登录失败次数超限时 `POST /login` 回 `429`。
- 下游不可达、鉴权失败或参数校验失败时，Admin 返回对应的 HTTP 错误状态；同时页面会显示失败提示。
- 指标聚合接口在旧版本组件缺少指标端点时，会返回包含 `error` 的结构化 JSON，而不是让整个仪表盘崩溃。
- 避免把 `X-Rover-Admin-Token`、协议 token、Cookie 或完整请求体写入日志。

## 轻量调用建议

- `/api/live` 只在仪表盘可见时按约 1 秒轮询；后台标签页和其他页面不持续拉取全量数据。
- `/api/overview` 适合低频探活，避免把它当作高频监控采集接口。
- `/api/traces` 的数据量受 Gateway 采样率和环形缓冲限制；排查慢请求时优先使用 `slow=1`，避免长期打开全量采样。
- 写接口成功后再刷新列表，避免重复提交同一个配置或路由变更。

Admin API 是同版本控制面接口。客户端只应依赖本文列出的路径、参数和字段，避免依赖未文档化的聚合内部字段；升级 Gateway、Nameserver 和 Admin 时应保持同一版本。
