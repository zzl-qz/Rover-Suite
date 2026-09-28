# Rover Ops Agent / 故障调查与受控处置 Agent

> **面向网关与微服务运行态的 AIOps 故障调查与受控处置 Agent。**
>
> Gateway-centric AIOps Incident Investigation & Controlled Remediation Agent

本文是 Rover Ops Agent 模块的产品定位锚点。后续该模块的功能取舍、优先级和架构决策，都以本文为准。

> **文档约定：** 本项目公开文档只描述当前代码已实现的行为。本文中每一节都标注了「已实现 / 规划中」，规划内容不代表现有能力。

## 1. 它是什么，不是什么

当用户主动发起排障，或监控系统产生告警后，Agent 收集网关路由、服务实例、指标、追踪等证据，完成故障调查、根因推断和处置建议；在权限与审批允许的前提下，进一步执行有限的运维动作并验证结果。

明确排除两种定位：

| 不是 | 原因 |
| :--- | :--- |
| 网关里的问答机器人 | 问答只是交互方式；价值在于基于真实运行态证据的调查链，而不是生成一段文本 |
| 自动告警机器人 | 确定性阈值判定应由监控系统承担，让 LLM 周期性轮询指标既昂贵又不稳定 |

## 2. 与监控系统的职责边界

```text
监控 / 告警系统（Prometheus、AlertManager、Rover Metrics）
        负责「发现有问题」——确定性规则判定
                    ↓
            Alert / Event
                    ↓
              Rover Ops Agent
        负责「调查为什么有问题」——证据收集与根因推断
                    ↓
            Incident Report
```

**监控系统负责发现问题，Agent 负责调查问题。** 两者不互相替代。

## 3. 目标能力链

```text
Detect → Investigate → Correlate → Diagnose → Recommend → Approve → Execute → Verify
```

| 阶段 | 归属 | 状态 |
| :--- | :--- | :--- |
| Detect（发现异常） | 监控 / 告警系统通过 `POST /api/agent/events/ingest` 送进来 | 已实现（被动接入）；主动定时巡检未实现 |
| Investigate → Diagnose（调查与诊断） | **Agent 的核心价值** | 进行中（只读调查链已用 Graph 编排，含假设验证） |
| Recommend（处置建议） | Agent | 进行中（已能生成不可执行的处置计划） |
| Approve / Execute / Verify（审批、受控执行、验证） | Agent，需配套治理层 | 规划中 |

## 4. 能力分级

### Level A：Observe（看）

Agent 能查询 Route、Instance、Metrics、Trace、Config、Events、Logs（历史日志）、Knowledge（运维知识）等运行态数据，全程只读。

**状态：已实现。** 当前 `InvestigationService`（`rover-agent-runtime`）由 `AgentOrchestrator` 驱动，采集路由、实例、指标、追踪、配置、注册事件、历史日志、运维知识八类快照，返回结论、证据来源和采集时间。其中指标除全局窗口外，还能按「路由 × 上游实例」取到窗口请求数、状态码、错误率与延迟，用于指出是哪台实例在出错；历史日志按类型 / 目标 / 时间范围过滤（配置变更、回滚、错误、实例上下线、指标采样、慢与错误链路），运维知识回答「怎么配置 / 怎么接入 / 怎么排查」。

### Level B：Reason（调查 + 推理）

Agent 能制定调查计划、动态选择工具、关联多条证据、排除错误假设、给出根因和处置建议。

**状态：进行中。** 「自主 + 推理」目前有两条并存的形态：会话主路径是模型自主——一句话进来后由模型自己决定查什么、
查几次、按什么顺序，边查边组织回答（详见 §7 第一条）；调查链仍是 StateGraph 编排，由节点采集事实、由条件边决定走向，
再由结论节点逐条确认、排除或标记为无法验证（例如「路径未命中路由」「目标服务没有匹配实例」「匹配实例均不健康」
「该路径近期返回 503」「该路由的某个上游实例返回了 5xx」），每个假设都绑定证据来源。

判定口径有明确边界：静态上游与服务发现模式属于路由事实，只写进结论与判断范围，不作为故障假设展示；
追踪不可用、追踪已关闭、或最近五分钟未采到该路径的 503 时，只能判为「无法验证」，不能写成「排除」——
采样与缓冲意味着「没采到」既不能证明没有请求，也不能排除同段时间内曾返回 503；近期 503 只在真正采到时才判为
「确认」，且只陈述观察到该路径返回 503，不推断该状态码由上游还是 Gateway 产生。证据里的追踪统计与判定共用同一个
五分钟窗口，缓冲区中更早的记录只单独说明条数、不计入判定，避免出现「证据里列着 503、结论说没采到」的自相矛盾。

两条路径的「选择权」不同，边界相同：调查路径由规划器产出计划、由执行器执行、由评估节点按证据决定
「继续规划 / 澄清 / 出结论」；会话主路径把这一步交给模型。不变的是——两者都只能从同一份能力注册表里挑已接入的 READ_ONLY 能力，
规划轮数、能力调用次数与计划步数三条硬边界由代码执行而不是提示词，
触顶即停止采集并在结论中如实说明。处置请求只会产出不可执行的处置计划（`executable` 恒为 `false`），
执行留给 Level C。

### Level C：Act（执行）

Agent 能在授权后执行有限的运维动作，例如摘除异常实例、调整路由、修改限流规则、触发回滚。

**状态：规划中。** 该级别必须配套 Policy、RBAC、风险分级、人工审批、幂等、回滚与结果验证，缺一不可。

## 5. 两个入口

```text
用户主动发起（已实现）                          事件 / 告警触发（已实现）
        │                                              │
        ↓                                              ↓
  目标解析（尽力而为）                            Incident Task
  显式指定 → 现有路由与实例 → 模型辅助                    │
  解析不出也照样对话（只有当次回答缺对象时才说明）          ↓
        │                                    调查编排 DynamicInvestigationGraph
        ↓                                    （PLAN → EXECUTE → EVALUATE 按证据循环，触顶停止）
  对话主路径 ToolLoopService                            ↓
  模型自己决定查什么、查几次、按什么顺序                synthesise
        │                                    （假设：确认 / 排除 / 无法验证 → 结论）
        ↓                                              ↓
  9 个只读工具（见 §7）→ 事实 + 依据 + 局限          假设式 Root Cause
        │                                              ↓
        ↓                                       Action Plan（只生成、不执行）
  边查边答，结论作为一条 Agent 回复写回会话
```

两条路径的取数口是同一个 `CapabilityExecutor`：模型与告警都无法越过它直连管理接口。区别在于「谁决定查什么」——
对话路径交给模型（9 个工具 + 调用预算），调查路径交给规划器（注册表里挑已接入能力，带假设验证）。

会话主路径不再先判「状态查询 / 故障调查 / 能力说明」再各走一套：一句话里问了几件事就查几次，答不了的那件如实说
「这一部分没查到」，不会因为其中一件事答不出就整句放弃。

- **用户主动发起：** 在 Admin「Agent 工作台」用一句话发起调查（如「为什么 /api/demo/tt 调用失败？」），
  可在折叠的「高级上下文」里手工指定 route / service / instance 与时间范围；追问沿用当前事件。**已实现。**
- **事件 / 告警触发（已实现）：** `POST /api/agent/events/ingest` 把一条告警转成「哪条路由 + 什么时间窗 + 怀疑什么」三要素，
  仅为机器调用（需登录，建议服务账号），送出后独立开会话、记一笔 `ALERT` 来源事件，并复用与人工提问完全相同的取数链路开展调查，
  回 `202` 与任务句柄。会话按事件隔离，不归属人工用户，因此不与人工会话争「单活跃任务」锁。
  尚未实现的是**主动**的那半边：定时自巡检、自建 `INSPECTION` 事件。

## 6. 架构取向

| 决策 | 选择 | 理由 |
| :--- | :--- | :--- |
| 编排方式 | 一个 Agent + 调查 Graph（Spring AI Alibaba StateGraph）+ 多个 Investigation Node | 运维任务有明显条件分支、长任务、人工审批和可恢复状态，不适合单一 ReAct 循环 |
| 是否 Multi-Agent | 暂不拆 | Metrics / Logs / Trace 本质是同一个 Incident 调查的不同步骤；仅当出现独立的上下文、权限、模型或生命周期时才拆 Sub-Agent |
| 模型与执行权 | 模型不持有执行权限 | 工具调用由应用侧执行；模型无法直接访问管理接口 |
| 工具治理 | 自研治理层 | Tool Schema 校验、RBAC、资源范围、超时重试、审计、高风险动作人工审批 |

**编排依赖：已采用 `com.alibaba.cloud.ai:spring-ai-alibaba-graph-core`（2.0.0-M1.1，对应 Spring AI 2.0.x 线）。**
只读调查链已经是一个 StateGraph：

```text
START → plan ──有可执行步骤──→ execute（逐条执行只读能力）→ evaluate ──证据足够──→ synthesise → END
          │                                                    │
          └──本轮无可执行步骤───────────────────────────────────┤
                                                               ├──证据不足且未触顶──→ 回到 plan（下一轮）
                                                               └──目标不明────────→ clarify → END
```

- 状态里只放标量（轮数、能力调用次数、本轮是否有可执行步骤、走向判定）；证据、证据局限、计划与结论放在图实例字段里，
  不从框架返回的状态回读。
- 规划只从能力注册表里选「已接入的只读能力」，执行器是唯一的取数口：越界的候选步骤被直接丢弃并记录局限，
  不出现无法执行或越权的步骤。
- 循环边界由代码执行而不是提示词：`rover.agent.planning.max-rounds`（默认 3）、`max-tool-calls`（默认 10）、
  `max-plan-steps`（默认 6）；触顶时停止采集，并在结论与步骤说明里如实标注「已达到规划上限」。
- 每次调查构建一个图实例：`invoke()` 返回的状态是框架的序列化快照，记录内部的嵌套集合与枚举在回读时会退化成
  `Map` / `List`（节点执行期间读写正常）。因此结论由 `synthesise` 节点直接产出、步骤上报口由节点闭包持有，
  二者都不从返回状态回读。

尚未启用的 Graph 能力是 checkpoint 与 interrupt：当前只读任务短、无需恢复，等进入 Level C 的审批与长任务阶段再评估。

## 6.1 代码布局与模块边界

Agent 已拆成三层，依赖单向：

```text
rover-admin（Web 契约 + 组合根 + 只读适配器）
        ↓
rover-agent-runtime（Spring AI + StateGraph 编排、任务生命周期、模型解读）
        ↓
rover-agent-core（纯 Java：领域对象、只读端口、中立快照、诊断规则；无 Spring、无 Jackson）
```

| 模块 | 包 | 职责 |
| :--- | :--- | :--- |
| `rover-agent-core` | `com.rover.agent.core.model` | Session / Incident / AgentMessage / Task / Step / Evidence / Hypothesis / InvestigationReport / TaskView / ResourceTarget / AgentIntent / IntentDecision / TaskType / ActionPlan |
| | `com.rover.agent.core.snapshot` | 中立只读快照：RouteSnapshot / RouteUpstreamSnapshot / InstanceSnapshot / GatewayMetricSnapshot / TraceSnapshot / TraceRow / ConfigEntrySnapshot / RegistryEventSnapshot / DiscoveryMode |
| | `com.rover.agent.core.port` | 只读端口：RouteReadPort / InstanceReadPort / MetricReadPort / TraceReadPort / ConfigReadPort / EventReadPort / LogQueryPort / KnowledgeReadPort（后两者配套 LogRequest / LogEntry / KnowledgeEntry）；数据不可用抛 SnapshotUnavailableException |
| | `com.rover.agent.core.repository` | 存储接口：AgentSessionRepository / AgentMessageRepository / IncidentRepository / AgentTaskRepository（内存 / 持久化实现可替换） |
| | `com.rover.agent.core.context` | AgentContextManager（最近 N 条消息 + 当前事件 + 结构化目标 + 关键证据）、TargetResolver / ResourceTarget（显式指定 → 现有路由与实例 → 模型辅助 → 澄清） |
| | `com.rover.agent.core.investigation` | RouteMatcher / EvidenceNarrator / InvestigationRules（纯函数，可脱离框架单测） |
| | `com.rover.agent.core.intent` | IntentClassifier / IntentDecision / IntentService：意图识别与取值约束（越界取值一律丢弃） |
| | `com.rover.agent.core.capability` | CapabilityDescriptor / CapabilityRegistry / CapabilityExecutor / CapabilityResult / AgentGrounding（环境画像与术语表）/ UntrustedText（不可信内容哨兵围栏）：能力清单与唯一的只读执行口 |
| | `com.rover.agent.core.planning` | InvestigationPlanner / InvestigationPlan / PlannedStep / PlanValidator / PlanningLimits / RuleBasedPlanner：计划生成、越界丢弃与硬边界 |
| `rover-agent-runtime` | `com.rover.agent.runtime` | AgentOrchestrator（应用入口：上下文 → 目标 → 任务 → 对话主路径）、InvestigationService（调查任务生命周期，告警接入仍走这条）、QueryStateService / ExplainService / ActionPlanService（状态查询、能力说明、处置计划）、ToolLoopService（模型自主取数主路径） |
| | `com.rover.agent.runtime.graph` | DynamicInvestigationGraph：plan / execute / evaluate / clarify / synthesise 节点与循环条件边、结论合成 |
| | `com.rover.agent.runtime.planning` | LlmInvestigationPlanner：规则打底 + 模型候选，越界步骤由 PlanValidator 丢弃 |
| | `com.rover.agent.runtime.task` | 任务生命周期、Session / Incident 登记（走存储接口，当前为内存、有界） |
| | `com.rover.agent.runtime.repository` | 4 个线程安全内存实现（重启即失），后续接持久化时替换 |
| | `com.rover.agent.runtime.tool` | SnapshotTools：把本次已采集的快照暴露给模型；OpsTools：9 个只读工具（对话主路径的数据入口） |
| | `com.rover.agent.runtime.knowledge` | InMemoryKnowledgeStore + `seedFaq()`：内置运维知识库，`KnowledgeReadPort` 的默认实现 |
| | `com.rover.agent.runtime.llm` | JsonCompletion / ModelExplainer / LlmIntentInterpreter / ModelTargetInterpreter / SpringAiJsonCompletion，对话侧另有 ChatModelGateway / ConversationModel / SpringAiConversationModel / NoopChatModelGateway（模型适配与未配置时的诚实降级） |
| `rover-admin` | `com.rover.admin.agent.adapter` | 6 个只读适配器：Route / Instance / Metric / Trace / Config / Event → 端口，不触发任何写操作 |
| | `com.rover.admin.log` | `H2LogQueryAdapter`（`LogQueryPort` 的实现，把字符串类型映射回 `RecordType` 查落盘记录库）与 TelemetryCollector（周期拉取 Gateway / Nameserver 运行态写入记录库，详见 6.2） |
| | `com.rover.admin.agent` | AgentController（会话 / 消息 / 任务 / 事件契约）+ 组合根 |

**边界规则：**

- `rover-agent-core` 不依赖 Spring / Jackson / Spring AI：Admin 管理口的 JSON 形状只在 `rover-admin` 的适配器里解析，
  核心规则用中立快照表达，因此可以直接单测，将来换数据源也不必改核心。
- 只读是结构性的：端口接口只有读方法，运行层拿不到 `saveRoute / deleteRoute / updateConfig`。
- Spring AI 与 Graph 依赖只出现在 `rover-agent-runtime` 与 `rover-admin` 两个模块；Admin 侧负责模型配置装配，便于后续把 Agent 挪到独立进程。
- 父工程 `spring-boot.version` 保持 3.2.0 不变：`rover-agent-runtime` 与 `rover-admin` 各自在模块内导入
  Spring Boot 4.1.1 与 Spring AI 2.0.1 BOM，互不影响。

**核心对象关系：**

```text
Session（一次连续对话）─┬─ Incident（一个被调查的问题，来源：USER / ALERT / INSPECTION）
                        └─ Task（一次调查执行）── Step（阶段进度）── Evidence（证据来源）
                                                              └── Hypothesis（假设：确认 / 排除 / 无法验证）
```

每次提问都会落到一个 Session 之下；只有故障调查（INVESTIGATION）才会创建一条 `USER` 来源的 Incident 并把 Task 挂在它之下，
状态查询 / 能力说明 / 处置计划等轻量任务不建事件（TaskView 的 `incidentId` 为空）。**连续追问已经可用**：同一个会话里的后续消息会带上最近 N 条消息、
当前事件、当前结构化调查对象与该事件的关键证据；目标一致时沿用当前事件，解析出明确的新对象时才另开事件，
解析不出对象时回澄清而不是猜（只有故障调查会因目标不明而澄清，能力说明类问题不走目标解析）。Session 与 Incident 目前只保留在内存中，**重启即清空**。

后续 Level C 所需的审批策略与事件接入会在此基础上继续拆分子包，避免为未实现的模块预建空壳。

## 6.2 模型执行策略

**状态：已实现。** Agent 按「任务形态」而不是「调用点」把模型调用分成两类：

| 调用类别 | 例子 | 用哪个模型 | 为什么这样分 |
| :--- | :--- | :--- | :--- |
| 推理型（长、生成式） | 「AI 解读」一次调查的结论 | 主模型，开 thinking | 需要深度推理，且它是最后一步综述，慢一点可以接受 |
| 廉价型（结构化、候选选择） | 意图识别、目标解析、调查规划 | 快速模型，关 thinking | 输出被约束在候选集里、且有规则兜底，速度与成本优先 |

这个分流落在 `ChatModelGateway` 端口上：`chatClient()` 主模型（thinking on），`chatClient(int timeoutSeconds)` 是专门的廉价调用入口，
配了快速模型就走它，没配就回落主模型并关掉 thinking。运行层只依赖端口，因此「怎么路由」是 Admin 侧实现的改动，运行层零改动。

模型选择收敛给运维而不是终端用户：控制台只需填「厂商 + API Key」，服务地址、主模型与快速模型由后台的 `ModelVendor` 映射
（`ZHIPU` → 主 `glm-4.6` / 快 `glm-4-flash`；`DEEPSEEK` → 主快共用 `deepseek-chat`）；
`CUSTOM` 给本地部署（Ollama / vLLM）留口子，地址与模型名手填。一个 Key 覆盖一家的模型家族，这层抽象才站得住。

实测取舍（真实智谱在线 API，30 条带标签的目标解析评测集，`TargetInterpreterEval.java`；最近一次复测 2026-09-28）：

| | glm-4.6（thinking） | glm-4-flash（当前快速模型） |
| :--- | :--- | :--- |
| 单条平均耗时 | 12.0 s | 1.1 s |
| 单次廉价调用成本 | 按厂商公开价目估算（本轮未实测 token） | 约 0（厂商免费额度） |
| 解析命中率 | 93.3% | 63.3% |
| **错误率**（答错对象，而非放弃） | 6.7% | 26.7% |
| 保守弃权率 | 0.0% | 10.0% |

口径：陷阱题与歧义题的期望答案就是「不给对象」，判为弃权即计命中，所以「命中率」里包含「正确地不猜」这一类；
glm-4.6 的 30 条里有 2 条是踩到 10 秒超时后转澄清而「侥幸正确」，不算它自己判断得好。

复测与首次评测（86.7% / 76.7%）有差距：30 条样本的单次波动本就不小，两次都不构成统计显著性。
但有一处结论被这次复测推翻了——首次评测里 glm-4-flash 的失败基本是「保守地转澄清」，这次它出现了明确的错归因
（把「trade-service 实例宕了」读成实例 `172.16.0.9:7001`），这正是当初否掉 `glm-4-air` 的同一类错误。
因此**「快速模型的错误性质可控」这句话目前没有稳定证据支持**。选型口径仍然是错误率而非命中率，
而快速模型只承接有规则兜底的廉价调用：目标解析判错会退化成「转澄清」，不会带着错的对象继续调查。
评测是独立程序而非常驻单测（依赖在线模型），30 条的规模是它的已知局限。

## 6.3 运行证据落库与遥测采集

「之前发生过什么」这类问题不能靠实时监控回答，因此 Admin 侧维护一份本地记录库（`RecordStore`）：数据在 Admin 运行时持续写入，与是否被提问无关 —— 没配模型也一样在写。

```text
写侧                                存储                                 读侧
Admin 配置写（变更 / 回滚 / 失败）─┐               ┌→ LogQueryPort ──→ CapabilityExecutor ──→ 工具 queryLogs
                                  ├→ RecordStore（本地 H2，双队列异步落盘）
遥测采集器（周期拉取管理口）─────┘               └→ （保留策略按类别清理）
内置 FAQ（InMemoryKnowledgeStore）────────────────────── KnowledgeReadPort ──→ 工具 searchKnowledge
```

**落库策略（`rover-common` 的 `RecordStore` / `H2RecordStore`）：**

- 双队列异步落盘：诊断证据类（critical）尽量不丢，队列满时短暂等待；遥测类量大，是 best-effort，满了直接丢弃，绝不让业务请求等待。
- 库位置 `rover.admin.log-store-path`（默认 `./rover-logs/rover`，实际文件 `rover-logs/rover.mv.db`）；JDBC URL 带 `AUTO_SERVER=TRUE`，因此应用运行期间可以用另一个进程以同一 URL 只读打开这份库做核对。
- 表结构演进走版本化迁移（`schema_migrations` 记录已应用版本，只前进不回退），老库按版本增量补 `ALTER`，不会像 `CREATE TABLE IF NOT EXISTS` 那样整段跳过。
- 数据只在本机磁盘上：换机或清目录即丢，不作为跨机审计源。

**写入内容（`RecordType`）：**

| 记录类型 | 语义 | 生产者 | 类别与保留期 |
| :--- | :--- | :--- | :--- |
| `CONFIG_CHANGE` | Admin 配置写成功：保存 / 删除路由、动态配置调整 | AdminConfigService | 诊断证据（`log-retention-days`，默认 30 天） |
| `ROLLBACK` | 回滚成功（回滚本身也是一次变更） | AdminConfigService | 同上 |
| `ERROR` | 配置写失败（含下发 Gateway 失败） | AdminConfigService | 同上 |
| `INSTANCE_EVENT` | 组件可达性翻转、实例注册 / 注销 / 健康翻转 | TelemetryCollector | 同上 |
| `METRICS_SAMPLE` | 每组件每周期一条聚合指标快照，用于看时间序列而不是单点 | TelemetryCollector | 遥测（`log-telemetry-retention-days`，默认 3 天） |
| `REQUEST_TRACE` | 慢请求与 5xx 错误链路，按 traceId 去重 | TelemetryCollector | 同上 |

预留但暂无生产者：`DEPLOY` / `AGENT_ACTION` / `MEMORY` / `HEARTBEAT` / `GENERIC`。原始心跳与逐请求日志刻意不入库：量大、对模型是噪声，正常态已能被指标采样的时间线覆盖。

**遥测采集（`TelemetryCollector`）：** 周期 `rover.admin.log-collect-interval-seconds`（默认 30 秒，下限 5 秒，首轮延迟一个周期等 Bean 就绪），每轮拉 Gateway / Nameserver 管理口的 STATUS / METRICS / INSTANCES / TRACES：组件级先判可达性翻转，实例级维护健康基线（**首轮只灌底不记事件**，避免启动瞬间把既有实例刷成一批上线事件），之后只在注册 / 移除 / 上 / 下翻转时记一条；指标按「组件 × 周期」写一条聚合快照；链路只取慢请求与 `error` 口径的 5xx，并用有界队列（1 万条）的 traceId 去重。清理任务每 60 分钟跑一次，按上面的保留期分别清理两类记录。

**开放给模型的两个只读能力：** 历史日志走 `LogQueryPort`（`queryLogs` 按类型 / 目标 / 时间范围查询），运维知识走 `KnowledgeReadPort`（`searchKnowledge`，数据来自 `InMemoryKnowledgeStore` 的内置 FAQ，不经记录库）。两者已经在 §7 的能力清单里登记为可用的 READ_ONLY 能力。

## 7. 当前实现程度（与代码保持一致）

**已实现：**

- 对话主路径 `ToolLoopService`（模型自主取数）：一次提问进来后，**由模型自己决定查什么、查几次、按什么顺序查**，
  而不是先把它归类成「状态查询 / 故障调查 / 能力说明」再各走一套固定的编排。每次工具调用都是一次真实取数，
  工具的共同执行体仍是 `CapabilityExecutor`——模型拿不到管理接口凭证，也没有任何写权限。共 9 个只读工具，
  与能力注册表一一对应：

  | 工具 | 读到什么 | 对应能力 |
  | :--- | :--- | :--- |
  | `listRoutes` / `getRoute(path)` | 路由与前缀、目标服务与分组、静态地址 / 是否剥前缀 | ROUTE_QUERY |
  | `listInstances(serviceName)` | 注册实例、地址端口、健康状态 | INSTANCE_QUERY |
  | `getGatewayMetrics(path)` | 全局窗口与「路由 × 上游实例」的请求数、状态码、错误率、延迟 | GATEWAY_METRICS_QUERY |
  | `getTraces(path)` | 抽样链路（Phase、耗时、状态码、TraceId） | TRACE_QUERY |
  | `getConfigs()` | Gateway / Nameserver 当前生效配置 | CONFIG_READ |
  | `listRegistryEvents()` | 最近注册 / 注销 / 剔除 / 健康标记事件 | EVENT_QUERY |
  | `queryLogs(type, target, hours)` | 落盘历史证据：CONFIG_CHANGE / ROLLBACK / ERROR / INSTANCE_EVENT / METRICS_SAMPLE / REQUEST_TRACE | LOG_QUERY |
  | `searchKnowledge(query)` | 内置运维 FAQ：怎么配置、怎么接入、怎么排查 | KNOWLEDGE_RETRIEVAL |

  每个工具的 description 都写明「读到什么、什么时候该用、什么时候不该用、拿不到什么」，模型因此不会去向路由快照要流量，
  也不会把「追踪无记录」当成「没有问题」。调用预算由两侧同时兜：`OpsTools.MAX_CALLS`（30）与
  `spring.ai.tools.limits.max-total-tool-calls`（30）保持一致，单工具默认上限 10；模型不可用时不假降级到规则流程，
  而是如实回「当前没有可用的模型，请在模型配置页填好后重试」——这条路径的每次取数与每句结论都出自模型，冒充不了。
- Admin「Agent 工作台」+ 会话式接口：`POST/GET /api/agent/sessions`、`POST /api/agent/sessions/{sessionId}/messages`、`GET /api/agent/sessions/{sessionId}/workspace`、`GET /api/agent/tasks/{taskId}`、`GET /api/agent/tasks/{taskId}/events`（SSE）；
  旧 `/api/agent/diagnoses*` 入口已随 `DiagnosisController` 一并移除，只保留会话式入口。
- 多轮追问：AgentContextManager 装配「最近 N 条消息（`rover.agent.context.recent-message-limit`，默认 8）+ 当前事件 + 结构化目标 + 关键证据」；
  TargetResolver 按「显式指定 → 现有路由与实例数据 → 模型辅助 → 澄清」解析对象，解析不出时不猜、不建任务。
- 单一编排入口 `AgentOrchestrator`：Controller 不再直接编排 route/metrics/chatClient 调用，只做参数校验与结果映射。
- 任务取消：`POST /api/agent/tasks/{taskId}/cancel` 是协作式取消（标记 `CANCELLED` 并中断执行线程，结论不再产出），已结束的任务返回 `409 TASK_NOT_CANCELLABLE`。
- 事件接入：`POST /api/agent/events/ingest` 把一次告警转成「路由 + 窗口 + 怀疑点」，独立开会话并记 `ALERT` 来源事件，
  复用与人工提问相同的取数链路开展调查；会话按事件隔离，不与人工会话争「单活跃任务」锁。
- 指标对外出口：默认进程内 `SimpleMeterRegistry`；开启 `management.metrics.export.prometheus.enabled=true` 后
  注册表切换为 `PrometheusMeterRegistry`，由 `/actuator/prometheus` 供抓取，指标名与标签口径不变（`rover.agent.*`）。
- 存储已抽象成 4 个 Repository 接口，当前只有线程安全内存实现，**重启即清空**；业务代码不直接依赖 Map。
- 用户身份一律取自后端认证上下文（`Authentication.getName()`），请求体不接受前端提交的 `userId`；会话、任务与事件按身份过滤。
- 只读采集路由、实例、指标（含「路由 × 上游实例」窗口观测）、追踪、配置、注册事件、历史日志、运维知识，产出结论、置信度、证据来源、采集时间和局限说明。
- 已拆成 `rover-agent-core` / `rover-agent-runtime` / `rover-admin` 三层，依赖单向；只读由端口结构保证。
- 运行证据落库与遥测采集（详见 §6.2）：配置变更 / 回滚 / 写失败、组件与实例健康翻转、按周期聚合的指标采样、慢与 5xx 链路
  都写进本地记录库，与用户是否提问无关。实时数据仍走管理口，历史证据才走 `queryLogs`。
- 意图识别与分流（**旁路分支，会话主路径已不走这里**）：消息先判定意图（`QUERY_STATE` / `INVESTIGATE` / `EXPLAIN` / `ACTION_REQUEST` / `KNOWLEDGE_QUERY` / 未开放的 `CREATE_INSPECTION`），
  再按需解析资源对象；状态查询只调少量只读能力直接回答，能力说明问题按注册表返回真实能力清单，未开放的请求如实回复而不硬走调查；
  识别不出意图时先看问题里有没有可解析对象，没有就回一句「我没太明白您的意思」+ 现在能做什么 + 两三条示例提问（完整能力清单只留给明确问
  「你能做什么」的人），不向用户追问「请给出请求路径」。
- 路径纠正（**旁路分支，会话主路径已不走这里**；证据驱动，不改「查询就是查询」的语义）：轻量路径真的给不出有用结果时，不把兜底话术直接甩给用户，而是换一条路径再试一次——
  状态查询说不出要查哪一类事实（「order-service 现在什么状态」）、或解释类问题会话里还没有可解释的结论时，只要问题点名的对象能解析出来，
  就按故障调查执行一次，并单独记一步「路径纠正」说明为什么换了形态。只在问题自己点了对象时纠正：缺少宾语的问法（「现在什么状态」）
  仍然如实回答「没说清要查哪一类事实」，不会拿上一轮的对象重启一次调查。
- 意图识别的提示词由三段组成：`AgentGrounding` 的环境画像与术语表（系统由 Gateway / Nameserver 组成、一次调用经过哪几环、
  用户口语各指什么）、同一份能力注册表生成的只读能力边界、以及意图取值与判别顺序（附少量示例）；「AI 解读」的提示词复用同一份画像，
  两处不会各自描述一遍系统。模型侧的策略是「只要与系统有关就必须选最接近的一类并如实给 MEDIUM / LOW」，`UNKNOWN` 只留给
  与运维完全无关的输入，不再把「说不准但明显在域内」的输入判死。规则层同时补了口语化故障词表（扛不住 / 时好时坏 / 无响应 / 502 …），
  确定性那层不漏，模型才是在补位而不是在填坑。
- 能力注册表与只读执行器：可用能力（路由 / 实例 / 网关指标 / 追踪 / 配置 / 注册事件 / 历史日志 / 运维知识检索）统一登记为 READ_ONLY 能力，规划只能从中选择，
  执行器是模型与生产数据之间唯一的取数口；未接入数据适配器的能力标记为不可选，处置类请求只生成不可执行的处置计划（`executable` 恒为 `false`）。
- 调查链由 Spring AI Alibaba StateGraph 编排：调查计划由规划器产出（规则打底、模型只提候选，越界步骤被丢弃），
  按「PLAN → EXECUTE → EVALUATE」循环推进，评估节点按证据决定继续规划、澄清还是出结论，触顶（`rover.agent.planning.*`）时停止并在结论中标注。
- 假设驱动结论：逐条确认或排除候选故障原因，每条假设标注状态（确认 / 排除 / 无法验证）、说明与证据来源。
- 诊断全程只读，不修改路由与配置。
- 控制台「模型配置」页可填写 OpenAI 兼容服务，保存即生效、无需重启 Admin；环境变量 `ROVER_AGENT_MODEL_CHAT` / `ROVER_AGENT_API_KEY` / `ROVER_AGENT_BASE_URL` / `ROVER_AGENT_MODEL` 只在首次启动、尚无模型配置文件时用于播种，之后以页面保存的配置为准。页面另支持连接测试（不保存）与验证已生效配置（`POST /api/model/verify` 回带生效版本号 `buildId` 与生效时间，可证明生效的正是刚保存的配置）。
- 模型未配置或不可用时的两种诚实降级：**对话主路径不做假降级**——它的每次取数与每句结论都由模型产出，因此直接回
  「当前没有配置可用的模型，请在模型配置页填好后重试」并用 `LOW` 结掉任务；调查链（Graph）仍会退化为纯规则诊断
  （`aiAnalysis` 为 null），采集与编排不受影响，并明确标注证据不足。
- 快速模型（可选）：`spring.ai.openai.fast-*` 可以给「意图识别 / 目标解析 / 调查规划」这类廉价调用配一个更小的模型，未配置则回退主模型；
  「AI 解读」与对话主路径始终用主模型，它的超时取模型配置里的值，不受 `quick-timeout-seconds` 影响。
- 这些「失败也能兜底」的小调用按 `rover.agent.llm.quick-timeout-seconds`（默认 10 秒）收紧等待上限，且只在超时后重试一次：偶发的网络卡顿能自愈，自愈不了就尽快走确定性兜底。
- 模型参与解释的证据全部来自只读快照，模型不直连管理接口：路由与实例这两份最小依据由运行时预读后写进提示词，指标与追踪按需经只读工具读取（工具由应用执行）。「AI 解读」步骤的结果说明会区分「运行时预读快照」与「模型另调工具」，解释依据可追溯到具体快照。每个只读工具的描述都写明「读到什么、什么时候该用、什么时候不该用、拿不到什么」，模型因此不会对着路由快照找流量，也不会把「追踪无记录」当成「没有故障」。
- 「AI 解读」边生成边推送：`ModelExplainer` 用流式调用把增量交给任务，Admin 通过 SSE 实时下发；最终全文仍落回任务结果，前端断线由轮询兜底。采集与规则判定是阻塞的 Graph 链路，不参与流式。
- 结论会作为一条 Agent 回复落进会话，且在任务定型之前写入：客户端收到 `TASK_COMPLETED` 后重新拉取会话，看到的就是答案本身，而不是「已开始 / 已继续调查…」的受理播报。对话路径的结论就是模型的回答全文，置信度如实记为 `MEDIUM`（事实有据可查，推理仍出自模型）；调查路径才会优先取模型解读、没有解读时回落规则结论。结论回调失败只 WARN，不会把已经跑完的任务判成失败。
- 每次提问都会落到 Session 之下，并尽力把这次任务挂到一个 Incident（解析出对象就按对象归集：同对象沿用、换对象另开；纯闲聊不建事件）。会话、任务、步骤与事件都存在 Admin 内存里，**重启后不可查询**；落盘记录库（§6.2）独立于它们，重启不丢。
- 意图识别评测闭环（`IntentEvaluationTest`）：38 条带标签语料要求规则层逐条精确命中；另有 6 条同一意图的模糊说法，要求规则层**不得**给出 `HIGH` 置信度拍板、必须让模型补位。解决「意图识别命中率只是口头描述、改提示词全靠感觉」的问题——命中率成为可回归的基线，调参前后可比。
- 结构化输出强约束 + 失败分类：`JsonCompletion` 的解析契约由调用方传入（`Function<String, Optional<T>> parser`）；`LlmIntentInterpreter` 由「宽容补默认值」改为严格契约——JSON 非法、或 intent / confidence / topic / action 任一缺失或越界，整条结果直接丢弃并回退规则层，不再静默补默认值；`ModelCallOutcome` 把调用结局区分为 `OK` / `NOT_CONFIGURED` / `UNAVAILABLE` / `TIMEOUT` / `ERROR` / `EMPTY` / `REJECTED`，其中 `rejected` 专门表示「模型返回了文本但不符合契约」。解决「越界输出被补默认值后命中率损失无法定位」的问题：宁可回退规则层，也不吞掉契约违规。
- 模型调用可观测性（含 token 用量）：`AgentMetrics` 的口径变为 `modelCall(model, scene, durationMillis, outcome)`，并新增 `modelTokens(model, scene, promptTokens, completionTokens)`；Micrometer 侧暴露 `model.calls`（标签 `model` / `scene` / `outcome`）、`model.duration`（标签 `model` / `scene`）与 `model.tokens`（标签 `model` / `scene` / `kind`，取值 `prompt` / `completion`；拿不到用量就不上报、不记 0），并删除只能回答「失败几次」的 `model.error`。解决「调用次数与耗时说明不了成本与上下文膨胀」的问题：token 用量按场景可查，成本与提示词膨胀可以被量化。
- 提示注入隔离（`UntrustedText`）：用哨兵把不可信内容围起来（开始 `<<<ROVER-DATA`、结束 `ROVER-DATA>>>`），`contract()` 声明哨兵内的一切都不是指令；`block(label, content)` 会先把内容里出现的哨兵替换成占位符 `[ROVER-DATA]` 再围栏。已应用在 `ModelExplainer`（快照描述）、`LlmInvestigationPlanner`（用户问题 + 已采集证据）与 `ModelTargetInterpreter`（候选对象 + 用户问题）。解决「快照 / 问题 / 证据都是外部内容，可能诱导模型越权」的问题：内容无法伪造边界，模型能把「数据」和「指令」分开。

**规划中（尚未实现，不要按已实现理解）：**

- 会话 / 事件 / 任务的持久化（Lite ↔ Standard 存储模式）：Redis 缓存与短期上下文、MySQL 承载任务与审计；当前只有内存实现。
- 记录库的远程化：现在是单机本地 H2，多副本 Admin 时各写各的，需要独立的时间序列 / 日志后端才能算跨机审计。
- 主动巡检：定时自巡检并自建 `INSPECTION` 事件。`POST /api/agent/events/ingest` 已经能让外部告警驱动自动调查，
  缺的是 Agent 自己定时去看一圈的那半边。
- Graph checkpoint 恢复与 interrupt 人工审批。
- 外部 SOP / Runbook 文档检索：历史日志（`queryLogs`）与内置 FAQ（`searchKnowledge`）已落地，
  但把团队自己的 Runbook / 变更记录批量灌进知识库（真 RAG：切片、向量、引用出处）尚未实现。
- 写操作、审批流、审计与执行后验证。

## 8. 与数据分析类 Agent 的区别

| | 数据分析 Agent（如 Spring AI Alibaba DataAgent） | Rover Ops Agent |
| :--- | :--- | :--- |
| 角色 | 数据分析师 | SRE / 运维诊断员 |
| 典型问题 | 销售额为什么下降？ | 服务为什么 5xx？ |
| 核心数据 | Database | Gateway + Instances + Metrics + Trace + 历史落库 + 运维知识 |
| 核心工具 | SQL / Python | 9 个只读工具：Route / Instance / Metrics / Trace / Config / Events / Logs / Knowledge |
| 最终产物 | 数据分析报告 | Incident Diagnosis |
| Action | 数据分析 | 运维处置（规划中） |
| 风险面 | SQL / 数据 | 生产基础设施 |
| 目标 | Insight | Diagnose + Remediate |

两者架构思路相近—— Rover Ops Agent 同样只有一个 Agent：会话主路径让模型在同一轮里自己决定查什么、查几次，
需要编排的复杂调查才交给 StateGraph（plan / execute / evaluate 循环），而不是把问题拆给多个 Sub-Agent 堆叠。
产品目标则完全不同。

## 9. 相关文档

- [Admin 使用手册](./admin-guide.zh-CN.md)：Agent 工作台使用方式与控制台模型配置
- [Admin API](./admin-api.zh-CN.md)：会话 / 事件 / 调查任务接口契约
- [配置项参考](./configuration-reference.zh-CN.md)：Agent 与落盘记录库的全部配置项
- [架构与权衡](./architecture.zh-CN.md)：Gateway 与 Nameserver 的既有设计