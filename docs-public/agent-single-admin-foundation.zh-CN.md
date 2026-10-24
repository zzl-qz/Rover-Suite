# Rover Agent 单 Admin 运行基础（S1）

本轮在一个 Admin JVM 内补齐资源身份、实时授权、H2 持久 Job、操作审计、任务事件重放和运行管理。仍只支持单个 Agent Admin；Gateway、Nameserver、业务服务各自运行，不属于多 Agent 协作。没有引入 PostgreSQL、消息队列或独立 Worker 服务。

这份交付证明当前范围内的开发与故障测试通过，不等同于整个生产 Agent 已经完成。自主调查提案、更多动作、业务效果验证、策略与审批期限等继续按 S2–S6 开发；PG/多进程/HA 在 S7。

## 资源和数据范围

- `ResourceScope` 包含组织、团队、环境，由 Admin 服务端配置决定。
- `ResourceRef` 进一步绑定管理目标、资源类型、资源 ID、UID、generation。路由编辑保留 UID；删除后同名重建生成新 UID。当前每个新 UID 的 generation 为 1，配置修改另受 Gateway revision 约束。
- 提案落库时保存 ResourceRef；批准、提交、回读和补偿均检查它。Gateway 的窄权重写接口也原子核对期望 UID/generation。同名重建不能继承旧批准。
- 管理口启用的 Gateway 首次启动即持久化初始路由身份。此后以管理存储中的路由为准；修改 YAML 不会覆盖已管理的路由，应使用受控管理入口。
- 会话、历史对话召回按 scope 和管理目标隔离；路由笔记进一步按 UID/generation 隔离。缺少可信 UID 的对象不生成可执行提案，也不写入新的路由记忆。
- 升级前无 scope 的历史会话保留为未绑定归档，不自动猜测它属于哪个环境。管理员可通过 `/api/agent/operations/legacy-sessions` 和 `/{id}` 读取；这些历史不会自动进入当前模型上下文，旧提案也不会自动补造 UID。
- `GET /api/agent/assets` 返回当前范围、revision、资源身份清单及 `identityComplete`。

当前授权粒度是“一个 Admin 的组织/团队/环境/管理目标 + 动作权限”，还没有按单条路由定义不同成员的权限。

## 账号、批准和机器来源

| 角色 | 能力 |
| --- | --- |
| VIEWER | 读取当前授权范围的数据和自己的会话 |
| OPERATOR | 读取、调查、提出方案、提交人工告警；不能批准，不能绕过 Agent 直接改 Gateway |
| APPROVER | 读取、查看同范围待审批提案、批准/拒绝/补偿；不会因此获得他人的聊天历史 |
| POLICY_ADMIN | 读取、管理模型设置和告警连接令牌；不能创建管理员或直接改生产资源 |
| ADMIN | 当前 Admin 的账号和运行管理，以及上述权限 |

一个账号可以有多个角色。实时查询账号是否启用、角色与资源范围；不相信旧 Cookie 中缓存的角色。后台工具、提交前和 SSE 投递也重新校验。批准权触发确定性执行器，不向模型或浏览器发放管理口凭证。

管理入口（浏览器登录后仍须提供 CSRF）：

| 接口 | 用途 |
| --- | --- |
| `GET /api/agent/security/me` | 当前主体和实时权限 |
| `GET/POST /api/agent/security/users` | 管理员读取/维护账号；新密码至少 12 字符，不能停用最后一个管理员 |
| `GET /api/agent/actions/pending` | 审批人查同范围待审批卡片 |
| `GET/POST /api/agent/security/connectors` | 查询/签发专用告警令牌 |
| `POST /api/agent/security/connectors/{id}/revoke` | 撤销令牌 |

账号请求示例：`{"username":"oncall","password":"通过受控通道设置的强口令","roles":["OPERATOR"],"enabled":true}`。更新已有账号可以不传 password。

机器令牌只能以 `Authorization: Bearer ...` 调用 `POST /api/agent/events/ingest`，不能读控制台、批准、改配置。秘密只在创建时返回一次，数据库和列表只留 SHA-256 摘要及元数据。默认有效期 30 天，最多一年；每次接入校验过期、撤销、范围和处理人权限。必须配置有效的 `rover.agent.alerts.owner-user-id`；事件来源保存为 `connector:名称`，处理人仍是服务端配置的人类账号。浏览器 Cookie 写请求继续校验 CSRF。

配置读取工具会遮蔽 token、password、secret、API key、credential 等敏感配置项的值和默认值。管理凭证只在宿主 HTTP 适配器内部使用。

## 持久任务和重启

正常 HTTP 受理路径先保存会话/任务和可重建 Job 输入，再返回任务句柄。调查与对话都通过 Admin 内原有有界线程池执行；库中 Job 可按持久唤醒时间调度。等待输入和等待批准不占 Worker。

启动恢复遵循以下规则：

1. 一个 H2 目录只允许一个 Agent Admin 持有本地文件锁。第二个实例拒绝取得执行权；不是多节点抢任务。
2. 上次未完成的只读 Job 重新入队，增加 attempt，并恢复输入、事件编号、已有证据与检查点。
3. 可重新读取当前事实，不假装旧观测仍然新鲜；规划轮数、工具次数、模型次数、token 和原截止时间不能借重启归零。未收到最终用量的模型调用按已预留输出保守计入。
4. 当前总只读工具预算为 30 次；重复模型尝试仍受同一模型次数/token/时限约束。
5. 快照、事件、检查点和预算写回检查当前 attempt，迟到回调不能覆盖新的执行状态。
6. 同一 Job 再次到达同一提案意图时复用原审批卡。只读恢复队列不重放外部写动作。
7. 原来已提交的写操作保持原 operationId；重启后结果未知则查询 Gateway 持久回执。没有足够证据时保持未知，不重新发一条变更。

旧版本没有 Job 输入的执行中任务仍标记为 INTERRUPTED，保留事实供人工复核。不能为缺失的输入编造续跑过程。

有界队列满时，已经持久化的 Job 留在库中等待调度；任务总容量仍有限制，同一会话仍只允许一个活动任务。排空期间新任务不受理。

## 审计与断线续读

Action 的每次状态写入和独立审计在一个事务提交；审计失败则业务状态不单独提交。审计保留主体、范围、资源身份、前后权重、revision、操作号和状态。账号/机器令牌/写停变更也记录安全审计，秘密不写入审计正文。

任务的状态通知及模型增量先写 H2 outbox，再投递 SSE。`Last-Event-ID` 可按任务编号续读；未提供游标时发送持久快照。刷新、客户端断线或进程重启后仍可读取。投递允许重放，客户端按事件编号去重。慢客户端不会阻塞调查 Worker。

删除会话会清理工作区查询数据，不删除独立执行审计。审计表没有业务删除接口，但同一台主机上的数据库管理员仍是信任边界；这里不声称它能抵抗数据库管理员篡改，也不声称恢复旧备份后仍能证明完整历史。外部不可变归档和长期留存策略继续按后续阶段建设。

## 运行、停写与备份

以下入口仅 ADMIN 使用：

| 接口 | 行为 |
| --- | --- |
| `GET /api/agent/operations` | 存储、写停、排空状态；持久库不就绪返回 503 |
| `POST /api/agent/operations/write-stop`，`{"stopped":true}` | 持久停止新生产写；恢复用 false；查询原操作结果仍可进行 |
| `POST /api/agent/operations/drain` | 停写并停止接收新调查，让已运行任务结束 |
| `POST /api/agent/operations/resume` | 存储就绪后恢复接收调查；不顺带解除写停 |
| `GET /api/agent/operations/audit?after=0&limit=100` | 按当前范围和游标读取独立审计 |
| `POST /api/agent/operations/backups` | 在库目录的 backups 下生成 UUID 命名的 H2 一致性 ZIP，返回本机路径 |

数据库使用 `WRITE_DELAY=0`。生产模式拒绝内存存储、默认登录口令、缺失管理凭证/稳定管理目标/明确环境。存储无法持久化批准、操作号或安全状态时，不能继续提交新写请求。

关闭时先排空；超过关闭等待时间的只读 Job 保存为待重启恢复，停止预算和回调，不伪装成人主动取消。恢复能力依赖原 Admin 进程重新启动，不提供自动故障转移。

推荐生产配置（敏感值通过环境或受控配置注入）：

```yaml
rover:
  agent:
    production-mode: true
    start-writes-stopped: true
    alerts:
      owner-user-id: oncall
  admin:
    bootstrap-password: ${ROVER_ADMIN_BOOTSTRAP_PASSWORD}
    admin-token: ${ROVER_MANAGEMENT_TOKEN}
    agent-organization-id: example
    agent-team-id: platform
    agent-environment-id: production
    gateway-control-target-id: gateway-production-a
    log-store-path: E:/rover-data/admin/rover
```

已有账号不会被 bootstrap-password 覆盖。`start-writes-stopped` 适合首次部署和恢复演练；解除后若该配置仍为 true，下次启动会再次停写。

恢复步骤：先停写、排空并停止 Admin；保留原库，在空目录用 H2 2.2.224 的 Restore 工具恢复 ZIP，然后让 Admin 指向恢复后的库并设置 `ROVER_AGENT_START_WRITES_STOPPED=true`。核对账号/范围、审计、任务状态和 Gateway 原操作号结果，再决定是否解除写停。禁止复制正在使用的 `.mv.db` 当备份，禁止在线覆盖当前数据库。

Admin 备份不代替 Gateway 的操作数据库/见证文件备份，也不包含外置模型配置文件和主密钥。恢复旧 Admin 快照前后发生的外部变更，必须用实际 Gateway 回执对账。

## 验收入口

- `GatewayResourceIdentityTest`：重启保留 UID，同名重建换 UID，旧 UID 写请求拒绝。
- `AgentActionServiceTest`：角色分权、另一审批人批准、预览期间撤权后不提交，以及原有执行/补偿/结果未知检查。
- `DurableJobRecoveryTest`：真实 Java 子进程 `Runtime.halt(73)` 后恢复 Job/预算，旧 attempt 拒绝写回，单 Admin 文件锁释放与互斥。
- `DurableFoundationTest`：ResourceRef 持久化、会话删除保留审计、审计失败回滚、事件游标续读、真实 H2 ZIP 备份恢复。
- `AgentAuthorizationWebTest` / `AgentSecurityServiceTest`：真实安全过滤器、机器入口、CSRF、撤权、过期/范围、持久写停和生产配置拒绝。
- `AgentContextTest`：真实 Spring 组合根中的 HTTP → Job → Worker → 工具 → 证据链。

完整命令、结果和真实进程回放产物见本轮交付验收记录。

2026-10-18 本轮按各模块最新报告去重，547 项 Java 测试、7 项脚本检查通过；真实回放完成 1920 次业务请求、4 次窗口故障调查及 1 次机器令牌告警，4 项回执检查和 13 项单 Admin HTTP 检查通过。产物在 `artifacts/agent-fault-replay/s1-foundation-20261018-02/`，本次 7 个 JVM 均已退出，19080～19085 端口已复核释放。
