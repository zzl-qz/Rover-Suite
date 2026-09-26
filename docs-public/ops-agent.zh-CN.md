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
| Detect（发现异常） | 监控 / 告警系统，以及后续的事件接入 | 规划中 |
| Investigate → Diagnose（调查与诊断） | **Agent 的核心价值** | 进行中（只读调查链已用 Graph 编排，含假设验证） |
| Recommend（处置建议） | Agent | 进行中 |
| Approve / Execute / Verify（审批、受控执行、验证） | Agent，需配套治理层 | 规划中 |

## 4. 能力分级

### Level A：Observe（看）

Agent 能查询 Route、Instance、Metrics、Trace、Events 等运行态数据，全程只读。

**状态：已实现。** 当前 `InvestigationService`（`rover-agent-runtime`）由 `AgentOrchestrator` 驱动，采集路由、实例、指标、追踪四类快照，返回结论、证据来源和采集时间。

### Level B：Reason（调查 + 推理）

Agent 能制定调查计划、动态选择工具、关联多条证据、排除错误假设、给出根因和处置建议。

**状态：进行中。** 只读链路的假设驱动调查已经落地：调查以 StateGraph 编排，由节点采集事实，由条件边决定走向，
再由结论节点逐条确认、排除或标记为无法验证（例如「路径未命中路由」「目标服务没有匹配实例」「匹配实例均不健康」
「该路径近期返回 503」），每个假设都绑定证据来源。

判定口径有明确边界：静态上游与服务发现模式属于路由事实，只写进结论与判断范围，不作为故障假设展示；
追踪不可用、追踪已关闭、或最近五分钟未采到该路径的 503 时，只能判为「无法验证」，不能写成「排除」——
采样与缓冲意味着「没采到」既不能证明没有请求，也不能排除同段时间内曾返回 503；近期 503 只在真正采到时才判为
「确认」，且只陈述观察到该路径返回 503，不推断该状态码由上游还是 Gateway 产生。证据里的追踪统计与判定共用同一个
五分钟窗口，缓冲区中更早的记录只单独说明条数、不计入判定，避免出现「证据里列着 503、结论说没采到」的自相矛盾。
尚未实现的是模型侧动态选择工具、
调查计划随证据自我调整，以及基于根因生成 Action Plan。

### Level C：Act（执行）

Agent 能在授权后执行有限的运维动作，例如摘除异常实例、调整路由、修改限流规则、触发回滚。

**状态：规划中。** 该级别必须配套 Policy、RBAC、风险分级、人工审批、幂等、回滚与结果验证，缺一不可。

## 5. 两个入口

```text
用户主动发起（已实现）          事件 / 告警触发（规划中）
        │                              │
        └──────────┬───────────────────┘
                   ↓
             Incident Task
                   ↓
          Investigation（调查编排：StateGraph）
                   ↓
   collectRoute ──条件边──→ collectInstances（仅当命中动态服务且为 Nameserver 发现时）
                   ↓
        collectMetrics → collectTraces → synthesise（假设确认 / 排除 → 结论）
                   ↓
   Events / Logs / Change / SOP（规划中）
                   ↓
             Root Cause
                   ↓
            Action Plan
```

- **用户主动发起：** 在 Admin「Agent 工作台」用一句话发起调查（如「为什么 /api/demo/tt 调用失败？」），
  可在折叠的「高级上下文」里手工指定 route / service / instance 与时间范围；追问沿用当前事件。**已实现。**
- **事件 / 告警触发：** 由 Alert 或 Gateway 事件自动创建 Incident 并启动调查。**规划中。**

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
START → collectRoute ──条件边──→ collectInstances ─┐
                └────（跳过）───────────────────→ collectMetrics → collectTraces → synthesise → END
```

- 节点只负责采集事实并写入共享状态，事实在节点间传递，不在方法内互相调用。
- 条件边按「路由是否命中 + 是否动态服务 + 服务发现模式是否为 Nameserver」决定是否跳过实例采集：
  这三类情况下实例数据对本次路由没有判定价值，跳过可减少无意义调用与误导性证据。
- 证据与局限用 `AppendStrategy` 累积，事实快照用 `ReplaceStrategy` 覆盖，避免节点间副作用。
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
| `rover-agent-core` | `com.rover.agent.core.model` | Session / Incident / AgentMessage / Task / Step / Evidence / Hypothesis / InvestigationReport / TaskView / ResourceTarget |
| | `com.rover.agent.core.snapshot` | 中立只读快照：RouteSnapshot / InstanceSnapshot / GatewayMetricSnapshot / TraceSnapshot / DiscoveryMode |
| | `com.rover.agent.core.port` | 只读端口：RouteReadPort / InstanceReadPort / MetricReadPort / TraceReadPort；数据不可用抛 SnapshotUnavailableException |
| | `com.rover.agent.core.repository` | 存储接口：AgentSessionRepository / AgentMessageRepository / IncidentRepository / AgentTaskRepository（内存 / 持久化实现可替换） |
| | `com.rover.agent.core.context` | AgentContextManager（最近 N 条消息 + 当前事件 + 结构化目标 + 关键证据）、TargetResolver / ResourceTarget（显式指定 → 现有路由与实例 → 模型辅助 → 澄清） |
| | `com.rover.agent.core.investigation` | RouteMatcher / EvidenceNarrator / InvestigationRules（纯函数，可脱离框架单测） |
| `rover-agent-runtime` | `com.rover.agent.runtime` | AgentOrchestrator（应用入口：上下文 → 目标 → 事件 → 任务 → 调查）、InvestigationService（任务生命周期） |
| | `com.rover.agent.runtime.graph` | StateGraph 定义、节点实现、条件边、结论合成 |
| | `com.rover.agent.runtime.task` | 任务生命周期、Session / Incident 登记（走存储接口，当前为内存、有界） |
| | `com.rover.agent.runtime.repository` | 4 个线程安全内存实现（重启即失），后续接持久化时替换 |
| | `com.rover.agent.runtime.tool` | SnapshotTools：把本次已采集的快照暴露给模型 |
| | `com.rover.agent.runtime.llm` | ModelExplainer：模型解读与未读证据时的拒绝策略 |
| `rover-admin` | `com.rover.admin.agent.adapter` | 4 个只读适配器：AdminConfigService → 端口，不触发任何写操作 |
| | `com.rover.admin.agent` | AgentController（会话 / 消息 / 任务 / 事件契约）+ DiagnosisController（旧入口，兼容转发）+ 组合根 |

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

当前每次提问都会落到一个 Session 之下：新建 Session 时会创建一条 `USER` 来源的 Incident，Task 挂在 Incident 之下；
TaskView 会带上 `sessionId` / `incidentId`。**连续追问已经可用**：同一个会话里的后续消息会带上最近 N 条消息、
当前事件、当前结构化调查对象与该事件的关键证据；目标一致时沿用当前事件，解析出明确的新对象时才另开事件，
解析不出对象时回澄清而不是猜。Session 与 Incident 目前只保留在内存中，**重启即清空**。

后续 Level B / Level C 所需的 tool 治理、审批策略与事件接入会在此基础上继续拆分子包，避免为未实现的模块预建空壳。

## 7. 当前实现程度（与代码保持一致）

**已实现：**

- Admin「Agent 工作台」+ 会话式接口：`POST/GET /api/agent/sessions`、`GET /api/agent/sessions/{sessionId}`、`POST /api/agent/sessions/{sessionId}/messages`、`GET /api/agent/tasks/{taskId}`、`GET /api/agent/incidents/{incidentId}`；
  旧入口 `POST /api/agent/diagnoses` 与 `GET /api/agent/diagnoses/{taskId}`（含 SSE 解读流）保留兼容，内部转发给同一套编排。
- 多轮追问：AgentContextManager 装配「最近 N 条消息（`rover.agent.context.recent-message-limit`，默认 8）+ 当前事件 + 结构化目标 + 关键证据」；
  TargetResolver 按「显式指定 → 现有路由与实例数据 → 模型辅助 → 澄清」解析对象，解析不出时不猜、不建任务。
- 单一编排入口 `AgentOrchestrator`：Controller 不再直接编排 route/metrics/chatClient 调用，只做参数校验与结果映射。
- 存储已抽象成 4 个 Repository 接口，当前只有线程安全内存实现，**重启即清空**；业务代码不直接依赖 Map。
- 用户身份一律取自后端认证上下文（`Authentication.getName()`），请求体不接受前端提交的 `userId`；会话、任务与事件按身份过滤。
- 只读采集路由、实例、指标、追踪，产出结论、置信度、证据来源、采集时间和局限说明。
- 已拆成 `rover-agent-core` / `rover-agent-runtime` / `rover-admin` 三层，依赖单向；只读由端口结构保证。
- 调查链由 Spring AI Alibaba StateGraph 编排，条件边可跳过对当前路由无判定价值的采集分支。
- 假设驱动结论：逐条确认或排除候选故障原因，每条假设标注状态（确认 / 排除 / 无法验证）、说明与证据来源。
- 诊断全程只读，不修改路由与配置。
- 控制台「模型配置」页可填写 OpenAI 兼容服务，保存即生效、无需重启 Admin；环境变量 `ROVER_AGENT_MODEL_CHAT` / `ROVER_AGENT_API_KEY` / `ROVER_AGENT_BASE_URL` / `ROVER_AGENT_MODEL` 只在首次启动、尚无模型配置文件时用于播种，之后以页面保存的配置为准。页面另支持连接测试（不保存）与验证已生效配置（`POST /api/model/verify` 回带生效版本号 `buildId` 与生效时间，可证明生效的正是刚保存的配置）。
- 模型未配置或不可用时，诊断自动退化为纯规则诊断（`aiAnalysis` 为 null），采集与编排不受影响，并明确标注证据不足。
- 模型参与解释的证据全部来自只读快照，模型不直连管理接口：路由与实例这两份最小依据由运行时预读后写进提示词，指标与追踪按需经只读工具读取（工具由应用执行）。「AI 解读」步骤的结果说明会区分「运行时预读快照」与「模型另调工具」，解释依据可追溯到具体快照。
- 「AI 解读」边生成边推送：`ModelExplainer` 用流式调用把增量交给任务，Admin 通过 SSE 实时下发；最终全文仍落回任务结果，前端断线由轮询兜底。采集与规则判定是阻塞的 Graph 链路，不参与流式。
- 每次提交会开启 Session 并创建 `USER` 来源的 Incident；任务与步骤状态存于 Admin 内存，重启后不可查询。

**规划中（尚未实现，不要按已实现理解）：**

- 会话 / 事件 / 任务的持久化（Lite ↔ Standard 存储模式）：Redis 缓存与短期上下文、MySQL 承载任务与审计；当前只有内存实现。
- 告警 / 事件接入与自动调查。
- 模型侧动态选择工具、调查计划随证据自我调整。
- Graph checkpoint 恢复与 interrupt 人工审批。
- 日志、变更记录、SOP / Runbook 检索（RAG）。
- 写操作、审批流、审计与执行后验证。

## 8. 与数据分析类 Agent 的区别

| | 数据分析 Agent（如 Spring AI Alibaba DataAgent） | Rover Ops Agent |
| :--- | :--- | :--- |
| 角色 | 数据分析师 | SRE / 运维诊断员 |
| 典型问题 | 销售额为什么下降？ | 服务为什么 5xx？ |
| 核心数据 | Database | Gateway + Metrics + Trace (Logs 规划中) |
| 核心工具 | SQL / Python | Route / Instance / Metrics / Trace |
| 最终产物 | 数据分析报告 | Incident Diagnosis |
| Action | 数据分析 | 运维处置（规划中） |
| 风险面 | SQL / 数据 | 生产基础设施 |
| 目标 | Insight | Diagnose + Remediate |

两者架构思路相近（都是「一个 Agent + Graph 编排 + 多个 Node」，而非多 Agent 堆叠），但产品目标完全不同。

## 9. 相关文档

- [Admin 使用手册](./admin-guide.zh-CN.md)：Agent 工作台使用方式与控制台模型配置
- [Admin API](./admin-api.zh-CN.md)：会话 / 事件 / 调查任务接口契约
- [架构与权衡](./architecture.zh-CN.md)：Gateway 与 Nameserver 的既有设计