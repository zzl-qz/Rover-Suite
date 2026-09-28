# Rover Ops Agent 改造报告（第 1~6 步落地）

> **后续变更提示（本轮之后）：** 本文描述的旧入口兼容层已在后续「死代码清理」轮中整体移除——`DiagnosisController`
> （含三个 `/api/agent/diagnoses*` 入口与旧 SSE 三事件语义）、零引用的 `TaskEventSink` 与失去字段的 `IncidentSeverity`
> 均已删除；`AgentController` 另删除了 `GET /api/agent/sessions/{sessionId}`（由 `GET /api/agent/sessions/{sessionId}/workspace`
> 取代）与 `GET /api/agent/incidents/{incidentId}`（零引用）两个冗余端点。项目处于开发阶段、不保留向后兼容。
> 本文以下内容为当时状态，保留作演进记录，读到 `DiagnosisController` / `TaskEventSink` 请以本提示为准。
>
> **再往后（P3.1 落库）：** 第 15 节「无持久化」一条已解决——会话、消息、事件、任务、步骤与证据现在写进记录库的
> 关系表（`agent_*` 六张表 + 版本化迁移），Admin 重启后整条链仍可查询；旧的整颗 TaskView JSON CLOB 已退役。
> 当前实现以 `ops-agent` §7 与 `admin-api` 为准。

本报告对应改造任务书第 37 节的十六项要求，记录本阶段（Message 提交进异步任务生命周期 → Workbench 事件化 →
仓储/安全/指标治理）的真实落地情况。所有结论以当前工作区代码与 `mvn test` 实测为准；本文只描述已发生的事。

---

## 1. 实际发现了哪些问题

按严重程度排序，均为改造前代码中可复现的问题：

1. **消息提交与任务生命周期脱节**：`POST /api/agent/sessions/{id}/messages` 在 Servlet 线程里同步跑完目标解析与调查，
   模型调用（`blockLast`）直接压在请求线程上，管理口一个慢模型就能拖住整个控制台的 HTTP 线程。
   澄清（目标解析不出来）走的是"返回值带 `clarification`、不建任务"的旁路，任务状态模型里根本没有这一档。
2. **SSE 只承载"AI 解读文本"**：前端要拿到步骤与状态只能轮询 `GET /api/agent/diagnoses/{taskId}`，
   四个阶段还是前端硬编码的；断线重连靠"补发全文"对齐前缀，没有事件序号概念。
   发送动作发生在任务状态锁里，慢客户端会拖慢模型调用与调查执行。
3. **旧 Diagnosis 接口无归属校验**：`GET /api/agent/diagnoses/{taskId}` 与 `/{taskId}/stream` 直接按 taskId 取内存记录，
   任何已登录用户猜到/拿到 UUID 就能读别人的任务与解读流（`POST` 入口才有身份透传）。
4. **一个会话可以并发起多个调查**：同一会话连点两次提问会并行跑两个任务，事件归属与"当前事件"指针互相覆盖。
5. **`AnalysisStreamListener` 耦合 Web 层**：运行层为了推 SSE 维护了一份"订阅者列表"，URL 语义（snapshot/delta/end）
   渗进了 runtime 包，核心模块无法脱离 Admin 复用。
6. **执行参数硬编码**：`WORKER_THREADS = 2`、`new ArrayBlockingQueue<>(16)`、`MAX_TASKS = 100`
   写死在 `InvestigationTaskRegistry`，不同部署环境只能改代码；且任务容量判断与存储容量判断是两套口径。
7. **BoundedStore 静默淘汰**：`put` 超容量时直接摘掉最早写入的条目（`keys.next(); keys.remove();`），
   被淘汰的可能是正在被引用的会话/事件，任务记录随之消失且无任何提示；容量问题表现为"记录莫名不见了"。
8. **主密钥与模型配置同目录**：默认 `master.key` 与 `admin-model.properties` 同目录，
   目录整体被拷走等于连密文一起解密；启动期没有任何提示。
9. **登录限流无条件信任 `X-Forwarded-For`**：`source(request)` 先读 XFF 首段，任何客户端补一个请求头
   就能把失败计数摊到不同"来源"上，5 次锁定形同虚设。
10. **没有任何 Agent 运行指标**：任务积压、模型耗时、SSE 连接数都只能靠日志，无法回答"是不是模型慢导致的"。
11. **仓储接口没有语义化查询**：会话/事件/任务只能 `listAll()` 后内存过滤，`removeMatching` 需要调用方自己写谓词，
    删除会话不会级联到其事件、消息与任务。

## 2. 哪些问题与任务书判断一致

- §4/§5/§6（提交进异步、澄清进状态模型、单会话单任务）与实际问题完全一致，且是本次改造的主线。
- §7/§8/§9/§10/§19/§20/§21（事件协议、SSE 是观察通道、运行层不该维护 Web Listener）与实际耦合形态一致：
  改造前确实是"运行层持有 listener 列表 + 文本流"。
- §12 描述的三个硬编码常量（2 / 16 / 100）逐一核实无误。
- §23 描述的"静默淘汰最早记录"在 `BoundedStore.put` 中逐字成立。
- §24、§25 描述的安全矛盾成立：默认主密钥同目录、限流无条件采信 XFF。
- §13 的权限漏洞成立，且范围比描述更大：不仅是 `GET`，`/{taskId}/stream` 同样没有归属校验。

## 3. 哪些地方实际代码与任务书描述不同

| 任务书描述 | 实际情况 | 处理 |
| --- | --- | --- |
| §12 建议 `task-capacity: 200` | 原值 `MAX_TASKS = 100`（任务书正文也写 100） | 按建议改为 200，属行为变化：登记容量扩大一倍，已在配置文档标注 |
| §12 说"参数校验 + 合理最小/最大边界" | 原代码无任何校验 | 新增 `AgentExecutionSettings` 记录，构造即校验（1~32 / 1~1000 / 1~10000），越界启动失败 |
| §13 表述为"权限漏洞" | 实际是"只有 POST 透传了身份，GET/stream 直接按 ID 取" | 三个入口统一走编排层的 `user(authentication)` 归属过滤，不属于当前用户统一 404 |
| §22 建议的仓储方法名 | 原接口方法较少（`listAll`/`removeMatching` 为主） | 按语义补齐 `findByUserId` / `findBySessionId` / `findByIncidentId` / `removeBySession` / `removeByIncident`，返回条数 |
| §26 指标口径 | 未描述具体指标名 | 落地为 `rover.agent.task.*` / `model.*` / `sse.connections`，标签只允许 `status`/`reason`/`model`，写入宿主进程唯一的 `MeterRegistry` |
| §24 "不要删除自动生成机制" | 原机制确实是首次加密时生成随机主密钥 | 保留；只新增"显式提供主密钥/独立目录"两条隔离路径与启动告警 |

## 4. 修改了哪些 Module

| Module | 改动性质 |
| --- | --- |
| `rover-agent-core` | 新增 `event` 事件协议包；删除 `AgentResponse`；`TaskStatus`/`TaskView`/`AgentMessage` 与四个仓储接口按语义化查询调整。仍为纯 Java（无 Spring / 无 Jackson） |
| `rover-agent-runtime` | 任务生命周期、事件总线、保留策略、执行参数、指标实现与装配；删除 `AnalysisStreamListener`；`pom.xml` 新增唯一允许的依赖 `micrometer-core` |
| `rover-admin` | 控制器契约（新端点 + 旧接口降级为兼容）、组合根装配 `SimpleMeterRegistry`、模型配置主密钥隔离、登录限流来源开关、前端静态资源 |
| 其它模块（rover-common / gateway / nameserver） | 未改动 |

## 5. 新增哪些类

**rover-agent-core（`com.rover.agent.core.event`）**

- `TaskEvent`：统一事件信封 `eventId / taskId / type / timestampMillis / payload`，`eventId` 在任务状态锁内单调递增。
- `TaskEventType`：`SNAPSHOT`、`TASK_CREATED`、`TASK_STARTED`、`STEP_STARTED`、`STEP_COMPLETED`、`STEP_FAILED`、
  `EVIDENCE_ADDED`、`ANALYSIS_DELTA`、`CLARIFICATION_REQUIRED`、`TASK_COMPLETED`、`TASK_FAILED`、`TASK_CANCELLED`。
- `TaskSnapshot`：任务视图 + 已产生的解读文本 + 已覆盖的事件序号 `coveredEventId`。
- `TaskEventSink` / `TaskEventSubscriber` / `TaskEventSubscription`：运行层发布口与订阅口，`TaskEventSubscription.NONE` 为无操作句柄。（`TaskEventSink` 已在后续清理轮移除，其余保留。）

**rover-agent-runtime**

- `task.AgentExecutionSettings`：worker 线程数 / 队列容量 / 任务登记容量，构造即校验边界。
- `task.TaskEventBus`：按任务分发的订阅中心，派发在总线线程上，不在任务状态锁里做网络 IO。
- `task.WorkspaceRetention`：三处准入（会话/事件/消息）+ 级联清理 + 清理顺序（`lastActiveAtMillis` 升序）。
- `task.TaskRetirement`：任务回收口，保留策略删任务必须走它，避免只删存储留下可被订阅的通道。
- `task.SessionLinks`：会话与事件的引用维护（挂载/摘除事件、维护当前事件指针），登记表与保留策略共用。
- `task.SessionTaskRunningException`：同会话已有执行中任务时抛出，携带 `runningTaskId`。
- `repository.StoreCapacityExceededException`：存储满且腾不出位置时的显式失败。
- `metrics.AgentMetrics`：指标端口（`NOOP` 空实现保证退化路径零成本）。
- `metrics.MicrometerAgentMetrics`：Micrometer 实现，模型名规范化并截断，标签基数有界。

**新增测试**：`MicrometerAgentMetricsTest`、`InMemoryRepositoryQueriesTest`、`WorkspaceRetentionTest`、
`InvestigationTaskEventsTest`、`TaskEventBusTest`、`AdminSecurityTrustedProxyTest`。

## 6. 重构哪些类

- `AgentOrchestrator`：提交只做"登记 + 入队 + 202"，目标解析与调查下沉到 worker；新增 `workspace(...)` 聚合与 `subscribeEvents(...)`；
  会话列表改为 `findByUserId`；消息落库改走保留策略准入。
- `InvestigationTask`：持有事件总线并发布结构化事件；新增 `waitForInput(...)`（澄清态）；
  终态与澄清态都上报一次指标（`settled` 防重）。
- `InvestigationTaskRegistry`：实现 `TaskRetirement`；worker/队列/容量全部来自 `AgentExecutionSettings`；
  拒绝原因分 `QUEUE_FULL` / `TASK_CAPACITY` 上报。
- `InvestigationService`：步骤推进改为事件化的 `STEP_STARTED/COMPLETED/FAILED`；解读增量发 `ANALYSIS_DELTA`；
  结论与证据一次性发 `EVIDENCE_ADDED` + `TASK_COMPLETED`。
- `ModelExplainer`：新增 `AgentMetrics` 计时/成败上报；`blockLast` 保留，但只发生在 Agent worker 线程上（注释已写明隔离约束）。
- `BoundedStore`：`put` 改为返回是否写入成功（满则拒绝），`size()/oldest()/removeMatching` 返回条数；不再静默淘汰。
- `InMemoryAgentSessionRepository` / `InMemoryIncidentRepository` / `InMemoryAgentMessageRepository` / `InMemoryAgentTaskRepository`：
  实现语义化查询与按会话/事件删除；容量满时抛 `StoreCapacityExceededException`。
- `IncidentRegistry`：三参构造接入保留策略；准入失败可见；会话/事件的摘除统一走 `SessionLinks`。
- `AgentController`：新增 `GET /sessions/{id}/workspace` 与 `GET /tasks/{id}/events`；提问改为 202 + 409/429 错误体；
  SSE 订阅者带连接数记账（只对确实建立过的连接加减）。
- `DiagnosisController`：整类标记 `@Deprecated`，三个入口全部转发编排层并做归属校验；SSE 改为把新事件翻译回旧文本字段。（已在后续清理轮整类移除。）
- `AgentCompositionConfiguration`：新增进程内唯一的 `SimpleMeterRegistry`（不引 Actuator/Prometheus）。
- `AdminModelProperties` / `SecretCipher`：主密钥解析优先级（属性 → 环境变量）、隔离判定与 `[SECURITY WARNING]`。
- `AdminSecurityProperties` / `AdminSecurityConfiguration`：`trust-forwarded-headers` 开关，成功/失败/限流三处共用同一 `source()`。
- core 仓储接口（`AgentMessageRepository`、`AgentSessionRepository`、`AgentTaskRepository`、`IncidentRepository`）、
  `TaskStatus`（新增 `WAITING_INPUT`、`active()`、`terminal()`）、`TaskView`（新增 `clarification`）、`AgentMessage`（关联 taskId）。
- 前端：`index.html`、`js/api.js`、`js/pages/workbench.js`。

## 7. 删除哪些类

- `com.rover.agent.core.model.AgentResponse`：被"202 + `TaskView` + 会话详情"取代，澄清改由 `WAITING_INPUT` 任务表达。
- `com.rover.agent.runtime.task.AnalysisStreamListener`：被 `TaskEvent` / `TaskEventBus` 取代。
- 测试 `InvestigationTaskAnalysisStreamTest`：被 `InvestigationTaskEventsTest` 取代。

## 8. API 变化

**新增**

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/agent/sessions/{sessionId}/workspace?limit=20` | 聚合：会话 + 对话 + 事件 + 最近任务（`limit` 上限 100） |
| GET | `/api/agent/tasks/{taskId}/events` | 任务事件流（SSE），事件名即事件类型 |

**语义变化**

| 接口 | 改造前 | 改造后 |
| --- | --- | --- |
| `POST /api/agent/sessions/{sessionId}/messages` | 同步返回 `AgentResponse`（含 `task`/`clarification`） | `202 {sessionId, taskId, status:"PENDING"}`；同会话冲突 `409 SESSION_TASK_RUNNING`；容量/队列满 `429 TASK_BUSY` |
| `GET /api/agent/sessions` | `listAll` 后过滤 | 按 `userId` 语义化查询 |
| `GET /api/agent/tasks/{taskId}`、`/incidents/{id}` | 已有归属校验 | 保持，未归属统一 404（`/incidents/{id}` 已在后续清理轮移除） |

**旧接口（保留兼容，均标记 `@Deprecated`；已在后续清理轮整体移除）**

- `POST /api/agent/diagnoses` → 转发编排层，返回任务详情（202）。（已在后续清理轮移除）
- `GET /api/agent/diagnoses/{taskId}` → 转发 `agent.task(taskId, user)`，**新增归属校验**（原实现无校验）。（已在后续清理轮移除）
- `GET /api/agent/diagnoses/{taskId}/stream` → 仍推 `snapshot` / `delta` / `end` 三个文本事件，数据源换成新事件总线，
  并新增归属校验。（已在后续清理轮移除）

## 9. Task State 变化

```text
PENDING → RUNNING → COMPLETED
                  ↘ FAILED
                  ↘ WAITING_INPUT（新增，停住等人补充）
CANCELLED（协议预留，尚无取消入口）
```

- `WAITING_INPUT`：目标无法确定时任务停在澄清点，**不算执行中**（`active()` 为 false），不占用会话并发位；
  澄清提问写入 `TaskView.clarification`，同时作为一条 AGENT 消息落进会话；用户补充后由会话的下一次任务继续
  （本阶段不做同一任务原地恢复）。
- `active() = PENDING | RUNNING`；`terminal() = COMPLETED | FAILED | CANCELLED`。
- 同一会话同时只允许一个 `active` 任务（409）；`WAITING_INPUT` 不挡后续提问。

## 10. SSE Event 协议

`GET /api/agent/tasks/{taskId}/events`，`text/event-stream`，超时 180s。

- SSE 事件名 = `TaskEventType` 名；`data` 为完整信封 JSON：`{eventId, taskId, type, timestampMillis, payload}`。
- 建连先补发一条 `SNAPSHOT`（`payload` = 任务视图 + 已产生解读文本 + `coveredEventId`），随后按 `eventId` 递增推增量。
- 载荷形状：状态事件 `{"status":...}`；步骤事件 `{"step":...}`；`EVIDENCE_ADDED` `{"evidence":[...],"count":n}`；
  `ANALYSIS_DELTA` `{"text":"..."}`；`CLARIFICATION_REQUIRED` `{"clarification":"..."}`；
  `TASK_COMPLETED` `{"status","summary","confidence"}`；`TASK_FAILED` `{"status","error"}`。
- 收尾：任务进入终态，或发出 `CLARIFICATION_REQUIRED`（本次观察结束，但任务不算终态）。
- 事实来源仍是 `GET /api/agent/tasks/{taskId}`：丢事件、断连、超时都不影响调查；重连靠 `SNAPSHOT` 对齐，不丢内容。
- 任务不存在 / 已不可观察 / 不属于当前用户 → `404`。

## 11. Executor 参数

```yaml
rover:
  agent:
    execution:
      worker-threads: 2      # 1~32
      queue-capacity: 16     # 1~1000
      task-capacity: 200     # 1~10000
    context:
      recent-message-limit: 8
    metrics:
      enabled: true          # false = 应急降级，不注册任何 Meter
```

- 不使用无界队列，不使用 `newCachedThreadPool()`；队列满 → `429`。
- 越界参数在构造 `AgentExecutionSettings` 时即失败，启动期暴露配置错误。
- 模型阻塞调用只占 Agent worker 线程：不阻塞 Servlet 请求线程，也不接触 Gateway 数据面。
- 任务登记容量从 100 提到 200（对齐任务书建议值）。

## 12. Workbench UI 变化

- 数据入口改为聚合接口 `workspace`，不再为每个任务各发一次请求（消除 N+1）。
- 步骤不再硬编码四个阶段：按 `STEP_STARTED/COMPLETED/FAILED` 事件动态渲染。
- 统一走 `EventSource` 订阅 `/api/agent/tasks/{taskId}/events`：`SNAPSHOT` 覆盖、增量追加、终态收尾；
  `EventSource` 不可用（或服务端故障）时降级为轮询 `GET /api/agent/tasks/{taskId}`。
- `409 SESSION_TASK_RUNNING` 不再是通用报错：前端直接复用返回的 `runningTaskId` 已有任务卡继续观察。
- `WAITING_INPUT` 有独立状态展示（"待补充"），澄清提问直接显示在任务卡与右栏"当前诊断"。

## 13. 安全修复

| 编号 | 问题 | 修复 |
| --- | --- | --- |
| §13 | 旧 Diagnosis 的 `GET`/`stream` 无归属校验（越权读他人任务与解读流） | 三个入口统一按认证身份过滤，未归属 404；与 Workbench 新端点同一套校验（相关入口已在后续清理轮移除） |
| §24 | 主密钥与模型配置同目录，密文可被整体拷走 | 显式主密钥（属性/`ROVER_ADMIN_MASTER_KEY`）或独立 `master-key-file` 目录视为已隔离；否则启动打 `[SECURITY WARNING]`，不阻断启动、不取消自动生成机制；密钥与密文不入日志与响应 |
| §25 | 登录限流无条件信任 `X-Forwarded-For`，可伪造来源绕过锁定 | 默认只认 `remoteAddr`；仅 `rover.admin.auth.trust-forwarded-headers=true` 时取 XFF 首段；成功、失败、限流三处共用同一来源函数 |
| §23 | 存储静默淘汰最早记录，可能丢掉仍在使用的会话/事件 | 存储满即拒绝（`StoreCapacityExceededException`）；由 `WorkspaceRetention` 决定清理谁、连不连子记录；有任务在跑的会话/事件不清理 |

## 14. 新增测试

**新增类**

- `MicrometerAgentMetricsTest`（6 项）：任务生命周期计数与活跃数、`WAITING_INPUT` 只记耗时不计完成/失败、
  拒绝原因规范化、模型标签规范化与错误计数、SSE 连接数不为负。
- `InMemoryRepositoryQueriesTest`（5 项）：会话按用户查询、任务按会话/事件查询与删除、事件按会话读取与批量删除、
  消息暴露最旧一条并返回删除条数、容量满时拒绝而不是淘汰已有记录。
- `WorkspaceRetentionTest`（4 项）：会话准入清理最旧空闲会话及其子记录、有任务在跑时拒绝而非清理、
  事件准入清理并从会话上摘除、消息准入丢弃窗口内最旧一条。
- `InvestigationTaskEventsTest`（5 项）：生命周期步骤与解读增量按序发布、迟到订阅者拿到含已产生文本的快照
  且之后只收增量、澄清与失败以事件表达、终态只上报一次指标、取消订阅后不再收事件。
- `TaskEventBusTest`（4 项）：慢订阅者丢弃自身积压并从快照重新对齐、慢订阅者不拖慢其他订阅者、
  终态任务的迟到订阅者先收快照再收终态事件、关闭的通道停止投递。
- `AdminSecurityTrustedProxyTest`（1 项）：开启 `trust-forwarded-headers` 后按 XFF 首段计数。

**改造既有用例**

`IncidentRegistryTest`（旧"静默淘汰"断言改为"满则拒绝"）、`AgentOrchestratorTest`（装配保留策略）、
`InvestigationServiceTest`、`AgentControllerWebTest`（202/409/429、workspace、事件流）、`DiagnosisControllerWebTest`（归属校验，已在后续清理轮随被测类一起移除）、
`ModelConfigStoreTest`（主密钥隔离判定）、`AdminSecurityWebTest`（默认忽略伪造 XFF）、`AgentContextManagerTest`。

**实测结果**（`mvn -o -pl rover-agent-core,rover-agent-runtime,rover-admin -am test`）
：rover-common 17 项、rover-agent-core 43 项、rover-agent-runtime 63 项、rover-admin 75 项，全部通过。

## 15. 尚未解决的问题

1. **无持久化**：会话、事件、消息、任务、事件总线全部在内存，Admin 重启即丢；保留策略只是内存 TTL 的替代品。
2. **指标没有对外出口**：`SimpleMeterRegistry` 只在进程内，未接 Actuator / Prometheus exporter，
   当前只能通过日志或宿主进程内的读取方式观察（本阶段刻意不引入外部依赖）。
3. **任务取消未接入**：`CANCELLED` 只有协议位，没有取消入口；`WAITING_INPUT` 的任务只能靠新任务继续。
4. **保留策略是"够用即止"**：容量满且全是执行中任务时会显式失败（而不是无界增长），
   但历史任务被淘汰后无法回溯，接数据库前没有历史查询能力。
5. **旧接口仍需双份维护**：`DiagnosisController` 的旧 SSE 语义（三个文本事件）与新事件协议并存，
   等旧前端下线后可整体删除。（`DiagnosisController` 已在后续清理轮整体移除，该双份维护问题随之消失。）
6. **模型配置默认仍是"开发友好"**：默认 `master.key` 与配置同目录，生产必须按配置文档改环境变量或独立目录，
   目前只有启动告警，没有强制。
7. **前端轮询降级未做端到端验收**：`§31` 的浏览器验收（事件流、动态步骤、409 提示、断线重连）尚未人工跑通，
   单测与接口测试已覆盖协议本身。

## 16. 下一阶段接数据库时的改造点

1. **仓储实现替换**：`AgentSessionRepository` / `IncidentRepository` / `AgentMessageRepository` / `AgentTaskRepository`
   的语义化方法（`findByUserId`、`findBySessionId`、`findByIncidentId`、`removeBySession`、`removeByIncident`）
   已是数据库友好的形状，直接落成 SQL/MyBatis；`StoreCapacityExceededException` 换成唯一约束/事务冲突。
2. **任务快照落库**：`TaskView` + `steps` + `evidence` 需拆表或在单表中序列化；
   `TaskSnapshot` 的 `coveredEventId` 语义可平移到事件表的自增序号。
3. **事件总线换持久通道**：`TaskEventBus` 的进程内订阅改为"事件表 + 轮询/游标读"或 outbox，
   SSE 订阅者按 `eventId > 已推送序号` 续读，重连语义不变（现有 `coveredEventId` 已为它预留）。
4. **保留策略改为数据库清理作业**：`WorkspaceRetention` 的准入/级联规则变成 TTL 清理与级联外键，
   "有执行中任务的会话不清理"的条件换成状态查询。
5. **多实例部署**：单会话单任务的并发位需要分布式锁或数据库乐观锁；
   `TaskRetirement` 的"进程内通道 + 存储"两件事要拆成"存储删除 + 事件通道关闭"。
6. **指标导出**：接 Micrometer 的注册表后端（如 Prometheus exporter 或 OTLP），指标名与标签口径保持不变即可直接复用。
7. **模型配置加密**：主密钥来源切到密钥管理服务/Secret 时，只需替换 `AdminModelProperties.resolveMasterKey()`
   的来源顺序，`SecretCipher` 与告警判定不动。