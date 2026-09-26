# Rover Ops Agent P2 改造报告：Intent / Capability / Dynamic Investigation

> 本报告只描述本轮改造实际落地的内容，与代码一致；测试结果取自 `rover-agent-runtime/target/p2-full-test.log`。
> 架构原则沿用任务书 §16：LLM 负责理解、计划与解释，Java 负责权限、执行、限制与确定性事实判断；
> Agent 只能调用已注册的只读能力；Dynamic Planning 不等于无限 ReAct；ActionPlan 不等于执行。

## 1. 新增 Intent

位置：`rover-agent-core` → `com.rover.agent.core.intent`（IntentClassifier / IntentDecision / IntentService），
领域枚举 `AgentIntent` / `IntentTopic` / `TaskType`（`com.rover.agent.core.model`）。

- `AgentIntent` 七个取值：`QUERY_STATE`、`INVESTIGATE`、`EXPLAIN`、`ACTION_REQUEST`、`CREATE_INSPECTION`、
  `KNOWLEDGE_QUERY`、`UNKNOWN`。本阶段真正执行前四类，后两类只识别、不假装执行。
- `TaskType` 五个取值：`QUERY`、`INVESTIGATION`、`EXPLAIN`、`ACTION_PLAN`、`UNSUPPORTED`；前端按它选择展示方式，
  而不是靠问题文本猜。
- 规则分类是底线，判定顺序刻意固定为「最容易被误判 → 最宽泛」：定时巡检 → 处置请求 → 能力咨询 → 解释/总结 →
  故障调查 → 状态查询 → 知识检索 → 未知。模型（`LlmIntentInterpreter`）只补规则说不清的场合，
  输出为受限 JSON（意图、topic、动作类型、时间范围），越界取值一律丢弃；模型未配置时退化为纯规则。
- 意图与目标分离：先判意图，再按需解析资源对象。`CAPABILITIES` 与全局 `METRIC` 查询不进入 TargetResolver——
  「你能做什么」不会再被要求澄清对象；只有故障调查会因目标不明而澄清。
- 轻量执行：`QUERY` / `EXPLAIN` / `ACTION_PLAN` / `UNSUPPORTED` 都不新建 Incident（不挂到事件下，
  `TaskView.incidentId` 为空），也不产出调查计划；`ACTION_PLAN` 只做一次只读预检。

## 2. 新增 Capability

位置：`rover-agent-core` → `com.rover.agent.core.capability`（AgentCapability / CapabilityDescriptor /
CapabilityRegistry / CapabilityExecutor / CapabilityResult）。

- 注册表是 Agent「能做什么」的唯一声明处，`CapabilityRegistry.standard()` 当前登记：
  - 可选（`selectable=true`，风险均为 `READ_ONLY`）：`ROUTE_QUERY`、`INSTANCE_QUERY`、`GATEWAY_METRICS_QUERY`、`TRACE_QUERY`；
  - 已登记但未开放（`available=false`，`supportedTargetTypes` 为空，Planner 不可选）：`CONFIG_READ`、`EVENT_QUERY`。
- 每个能力声明：编号、名称、说明、风险等级、支持的目标类型、是否已接入、对应的步骤类型与步骤名。
- 执行器是模型与生产数据之间唯一的取数口：未知能力与未开放能力不执行；取数失败或数据不可用转成
  `limitations`（如实说明），不产生假证据。指标窗口固定为最近 60 秒（`CapabilityExecutor.METRIC_WINDOW_SECONDS`）。
- 「你能做什么」的回答直接来自注册表 `describe()`，同一份注册表同时约束 Planner 可选集合与用户可见清单，
  模型不会声称系统具备未实现的能力；文案明确「处置类请求只生成不可执行的处置计划，当前版本不执行任何写操作」。

## 3. 新增 Planner

位置：`rover-agent-core` → `com.rover.agent.core.planning`，运行层实现 `com.rover.agent.runtime.planning.LlmInvestigationPlanner`。

- `InvestigationPlanner` 接口：`plan(PlanningRequest)` 产出计划、`evaluate(PlanningRequest, PlanProgress)` 决定
  「继续规划 / 澄清 / 出结论」；`InvestigationPlan(goal, hypotheses, steps)`、`PlannedStep(capability, reason, target, required)`。
- `PlanValidator` 是模型的边界，逐条校验、不满足即丢弃（不中断调查）：
  1. 能力必须在注册表里已接入且只读（未登记的能力名，含 shell / SQL 之类，一律拒绝）；
  2. 步骤目标归一化到请求目标，模型不能借计划扩大调查范围；
  3. 目标类型必须被该能力支持；
  4. 同一能力只保留一次，已处理过的能力不再重复规划；
  5. 步数截断到 `maxPlanSteps`。
- `PlanningLimits(maxRounds, maxToolCalls, maxPlanSteps)` 默认 `3 / 10 / 6`，构造器校验正数，
  配置越界时装配直接失败（`rover.agent.planning.*`），不留到运行期以「任务莫名被拒」出现。
- 装配：`new LlmInvestigationPlanner(new RuleBasedPlanner(registry), new SpringAiJsonCompletion(...))`——
  确定性规则打底，模型只提出候选，越界步骤由 `PlanValidator` 丢弃；模型未配置时计划完全由规则产出。

## 4. Graph 变化

旧链路是固定链：`collectRoute → collectInstances（条件边跳过）→ collectMetrics → collectTraces → synthesise`。
现在替换为 `DynamicInvestigationGraph`：

```text
START → plan ──有可执行步骤──→ execute（逐条执行只读能力）→ evaluate ──证据足够──→ synthesise → END
          │                                                    │
          └──本轮无可执行步骤───────────────────────────────────┤
                                                               ├──证据不足且未触顶──→ 回到 plan（下一轮）
                                                               └──目标不明────────→ clarify → END
```

- 节点：`plan` / `execute` / `evaluate` / `clarify` / `synthesise`；条件边决定循环、澄清或收尾，
  每次调查构建一个图实例。
- 状态里只放标量（taskId、path、question、轮数、能力调用次数、本轮是否有可执行步骤、走向判定）；
  证据、证据局限、计划与结论放在图实例字段里——`invoke()` 返回的状态是框架的序列化快照，
  嵌套集合与枚举回读会退化成 `Map` / `List`，结论由 `synthesise` 节点直接产出、步骤上报口由节点闭包持有，
  二者都不回读。
- 硬边界在代码里执行而不是提示词：能力调用达到上限后不再发起新调用，本轮未执行步骤如实上报
  「未执行：CAPABILITY」；轮数或调用次数触顶时追加局限说明，步骤说明写明「已达到规划上限（轮数 X/Y，能力调用 A/B）」。
- 轻量任务不走图：`QueryStateService`（按问法只选一个只读能力、不做假设判定、不进入模型解读）、
  `ExplainService`（能力清单/上下文解释）、`ActionPlanService`（处置计划）直接执行，保证「网关 QPS 多少」不会跑完整调查。

## 5. 新支持的自然语言请求

以 §13 验收口径为准（均已在 `IntentFlowTest` 中固化）：

| 问法 | 意图 | 形态与落点 |
| :--- | :--- | :--- |
| 网关 QPS 多少？ | `QUERY_STATE` | `TaskType.QUERY`，只调 `GATEWAY_METRICS_QUERY`，无计划、无事件；回答含最近 60 秒窗口请求数与拒绝数 |
| order-service 几个健康实例？ | `QUERY_STATE` | `TaskType.QUERY`，目标解析为 `service(order-service)`，只调 `INSTANCE_QUERY`，回答健康实例数 |
| 为什么 /api/demo/tt 调用失败？ | `INVESTIGATE` | `TaskType.INVESTIGATION`，建 `USER` 来源 Incident，动态计划（路由 → 实例 → 指标 → 追踪）并循环推进 |
| 追问「为什么没有实例？」 | `INVESTIGATE` | 沿用同一 Session 与 Incident，携带最近消息、结构化目标与该事件证据 |
| 你能做什么？ | `EXPLAIN` | `TaskType.EXPLAIN`，不进入目标澄清，按注册表返回真实能力清单（含未开放能力与「不执行写操作」说明） |
| 你好 / 看不出意图的闲聊 | `UNKNOWN` | `TaskType.EXPLAIN`：不进入澄清，回一段自我介绍 + 能力清单（同一份注册表），告诉用户可以怎么问；没有对象线索时连目标解析都不跑 |
| 把 order-03 摘掉 | `ACTION_REQUEST` | `TaskType.ACTION_PLAN`，目标含 `order-03`，预检 `INSTANCE_QUERY`，产出 `executable=false` 的处置计划 |
| 每天 9 点自动巡检并发邮件 | `CREATE_INSPECTION` | `TaskType.UNSUPPORTED`，明确回复「定时巡检尚未开放」，不进入调查、不建事件 |

## 6. ActionPlan 结构

位置：`com.rover.agent.core.model.ActionPlan` / `ActionType`，运行层 `ActionPlanService`。

- `ActionType` 五个取值：`DRAIN_INSTANCE`（摘除实例）、`RESTORE_INSTANCE`（恢复实例）、
  `UPDATE_ROUTE_TIMEOUT`（调整路由超时）、`UPDATE_RATE_LIMIT`（调整限流）、`UNKNOWN`（未识别）；
  前四类带 `targetsInstance()` / `targetsRoute()` 语义判定，其余一律归入 `UNKNOWN` 并如实告知。
- `ActionPlan` 字段：`actionType`、`targetDescription`（用户原文，如 `order-03`）、`target`（预检解析出的对象，
  未解析出时为 `ResourceTarget.unknown()`）、`reason`、`riskLevel`、`currentState`、`desiredState`、
  `expectedImpact`、`verificationPlan`、`rollbackPlan`、`executable`、`blockedReason`。
- `executable` 恒为 `false`：不是调用方自觉，而是 record 紧凑构造器里的硬约束——审批、审计与任务持久化尚未落地，
  不存在「可执行的处置计划」这种对象；`blockedReason` 缺省为统一措辞
  「当前版本尚未实现审批、审计与任务持久化，仅生成处置计划，不执行任何写操作。」，前端与会话回复引用同一处。
- 生成流程（`ActionPlanService`）：目标由编排层解析（解析不出时以用户原文保留在计划里），服务按动作类型选一个只读能力做预检——
  实例类动作（摘除 / 恢复）读实例快照，路由类动作与未识别动作读路由事实；预检证据留在任务报告里，
  人工照单执行前可核对当前状态，能力使用情况也随任务快照暴露（本次只有这一次预检调用）。
- 风险与容量口径绑定：摘除实例时若预检显示该服务只剩这一个健康实例，风险升级为 `HIGH` 并在预期影响里写明
  「摘除后该服务将没有健康实例」，而不是按动作名称字面轻重；`UNKNOWN` 动作一律按 `HIGH` 处理。
- 动作集合刻意窄：无法判定动作时仍出计划，但标注 `UNKNOWN`「未能识别为本版本支持的处置动作」。

## 7. 测试结果

执行命令与结果（真实输出，日志 `rover-agent-runtime/target/p2-1-full-test.log`）：

```bash
mvn -pl rover-agent-core,rover-agent-runtime,rover-admin -am test
```

| 模块 | Tests run | 结果 |
| :--- | ---: | :--- |
| rover-common | 17 | 全绿 |
| rover-agent-core | 67 | 全绿 |
| rover-agent-runtime | 73 | 全绿 |
| rover-admin | 75 | 全绿 |
| 合计 | 232 | BUILD SUCCESS |

新增与关键回归测试：

- 新增：`IntentClassifierTest`（11，意图分类、取值边界与兜底口径）、`CapabilityRegistryTest`（5，注册表、能力清单与自我介绍）、
  `PlanValidatorTest`（8，越界丢弃与硬边界）、`DynamicInvestigationGraphTest`（3，规划轮数 / 调用次数 / 只选已登记能力）、
  `IntentFlowTest`（7，§13 七个验收 Case 的端到端分流 + 识别不出意图时的自我介绍兜底）。
- 回归：`InvestigationServiceTest`（16）、`AgentOrchestratorTest`（12）等全部通过；既有 Session / Incident / Task /
  Evidence / SSE / Async Worker 行为未被破坏。

§15 十二项测试清单与覆盖位置：

| 测试项 | 覆盖 |
| :--- | :--- |
| Intent classification | `IntentClassifierTest` |
| Intent-Target separation | `IntentClassifierTest` + `IntentFlowTest`（能力咨询不进澄清） |
| QUERY_STATE lightweight execution | `IntentFlowTest`（单能力、无计划、无事件） |
| Planner selects only registered capabilities | `PlanValidatorTest` + `DynamicInvestigationGraphTest` |
| Planner max rounds | `DynamicInvestigationGraphTest` |
| Planner max tool calls | `DynamicInvestigationGraphTest` |
| Unknown capability rejected | `PlanValidatorTest` |
| Simple query does not run full investigation | `IntentFlowTest` |
| Investigation regression | `InvestigationServiceTest` + `AgentOrchestratorTest` |
| Follow-up context | `AgentOrchestratorTest` + `IntentFlowTest` |
| ACTION_REQUEST produces non-executable ActionPlan | `IntentFlowTest` |
| SYSTEM_HELP reports real capability set | `IntentFlowTest` + `CapabilityRegistryTest` |

改造过程中修复的问题：轻量任务解析出目标后只在「有活动事件」时才绑定，导致新会话里
「order-service 几个健康实例？」退回注册表全局口径（实测 target=`UNKNOWN`）。修复为新增
`bindResolvedTarget`——没有事件时也把对象落到任务上（`task.bind(null, path, target)`），
runQuery 与 runActionPlan 的已解析分支共用。

## 8. 剩余限制

- **ActionPlan 不执行**：本阶段没有任何写操作入口；审批流、审计、任务持久化缺一不可（Level C）。
- **未实现清单**（任务书 §14 明确排除）：MySQL / Redis 持久化、MCP、RAG、日志接入、Scheduler 与邮件通知、
  真实写操作、Approval、PolicyEngine、Multi-Agent、Shell / SQL。
- **能力面窄**：只接入 4 个只读能力；`CONFIG_READ` / `EVENT_QUERY` 已登记但无数据适配器，Planner 不可选。
- **模型侧不稳定由规则兜底**：意图与计划候选依赖受限 JSON，模型未配置或输出越界时退化为纯规则路径，
  能力清单类问题永远来自注册表而不是模型自由发挥。
- **数据侧固有边界**：追踪为抽样且有缓冲上限，未采到只能判「无法验证」；指标窗口固定最近 60 秒；
  同一时间窗口内证据与判定口径一致，窗口外记录只报条数不计入判定。
- **规划上限即停止**：触顶（默认 3 轮 / 10 次调用 / 6 步）后不再采集，结论中如实标注，复杂问题可能证据不足。
- **存储与验收**：Session / Incident / Task 仍在内存，重启即失；浏览器端人工验收（事件流、动态步骤、
  409 提示、断线重连）未执行，目前只有接口与单元测试覆盖。

## 9. 下一阶段建议

1. **Level C 治理层先行**：审批、审计与任务持久化落地后，才把 `executable` 从硬约束放开；
   处置动作必须带幂等、回滚与执行后验证。
2. **事件接入**：Alert / Gateway 事件自动创建 Incident 并触发调查，复用现有动态图与能力执行器，不新增执行路径。
3. **补齐能力适配器**：接入 `CONFIG_READ`（路由/限流配置读取）与 `EVENT_QUERY`（变更与告警事件），
   让「为什么刚变更后就失败」这类问题有据可查；两者仍是只读。
4. **Planner 评估更细**：evaluate 节点按「证据价值 / 边际收益」决定是否继续，配合成本预算（轮数与调用次数可配但要有默认值）。
5. **持久化（Lite ↔ Standard）**：Redis 承担短期上下文、MySQL 承载任务与审计，替换现有内存仓储；
   仓储接口已经就位，替换不影响业务代码。
6. **浏览器端端到端验收**：把 §13 七个 Case 在 Workbench 上真实走一遍（含 SSE 断线重连与 409 提示），
   作为下一阶段的验收前置。
7. **文档同步**：本轮已更新 `admin-api`（P2 任务字段、SSE 覆盖语义、「支持的问法与落点」表）、
   `configuration-reference`（`rover.agent.planning.*` 三参数）与 `ops-agent`（动态计划、能力注册表、ActionPlan 边界）。

尚未实现的能力一律不出现在注册表的可选集合里——这是本阶段最重要的长期约束：
**Agent 变聪明的部分全部来自「能选择什么」与「怎么循环」，而不是获得了更多权限。**