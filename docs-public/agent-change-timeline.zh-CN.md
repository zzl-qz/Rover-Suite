# S2-03：发布、配置与实例统一时间线

日期：2026-10-20。当前形态继续为单 Admin + H2；本轮不引入新数据库、第三方 SDK 或独立 Agent 进程。

现在调查除了知道“哪些请求失败”，还能查到“故障之前发布了什么、路由权重或配置怎么变了、实例状态何时被观察到变化”。时间接近只构成线索，不自动确认发布就是根因，也不生成可执行处置计划。

## 数据从哪里来

复用 `TelemetryCollector` 的调度，分别拉取 Gateway 路由、Nameserver 实例、Gateway 内置运行配置和 Nameserver 内置运行配置。每个源独立成功或失败，某个组件不可达不会把其他组件的采集结果丢掉。

新事件同步写入既有 H2 Agent 库的 `agent_change_event`、资源关联表和采集源基线表。事件、关联索引和新基线在同一事务提交；发布接收与审计同事务。读取失败抛出异常，不返回伪造的空历史。采集失败保留上次成功快照，不能把“拉不到实例”记录成“所有实例已移除”。

- 首次成功采集记录 BASELINE，明确表示首次观测，不表示刚发布或刚注册。
- 后续记录 APPEARED、CHANGED、REMOVED，并保留前后事实；路由保留 UID、代次、服务、分组权重、目标顺序、粘性请求头及源提供的配置 revision。仅全表 revision 改变不会虚构本路由配置变化。
- 轮询事件的精确发生时间为未知，保存两次成功观测之间的区间；间隔内可能出现多次变化。
- Admin 重启后沿用持久基线，不重造整批“新注册”。失败、重启、超过三个采集周期或本机时钟倒退形成采集空档标记。
- 实例按服务、分组和来源 instanceId 区分，保留地址、健康、权重和临时实例标记；没有实例生命周期 UID 时，不能据同地址或同名认定为同一生命周期。

旧 `records` 中没有可信范围或完整身份的历史不会自动升级成新时间线。它们仍可由原历史日志工具读取，并保留旧证据质量边界。

配置只记录内置数值、布尔值和枚举设置；插件属性和任意秘密字段不进入该时间线。静态上游 URL 去掉用户凭据、query 和 fragment。不能据此宣称敏感配置或所有插件配置均已被观测。

## 发布事件接入

发布平台通过 `POST /api/agent/releases/ingest` 推送事实；当前提供通用接入契约，未绑定某一家 CI/CD 平台。请求示例：

```json
{
  "sourceEventId": "pipeline-123:completed",
  "releaseId": "pipeline-123",
  "artifact": "registry/orders@sha256:build-digest",
  "serviceName": "orders",
  "group": "v2",
  "status": "SUCCEEDED",
  "occurredAtMillis": 1792400000000
}
```

发生时间应填写发送端真实的毫秒时间戳。支持 STARTED、SUCCEEDED、FAILED、ROLLED_BACK；不同阶段使用不同 sourceEventId。可选路由 ID、前缀、UID 与 generation；UID 与正 generation 必须同时提供。缺失身份不从当前配置补写。

组织、团队、环境、管理目标、来源身份和接收时间由服务器确定。发布 ID、制品及状态是已认证来源的申报，不等于 Rover 已验证制品存在或发布实际完成。请求和制品标识不应包含凭据。

通过既有 `POST /api/agent/security/connectors` 创建 `purpose=RELEASE` 的机器令牌。旧令牌和未指定用途的新令牌默认为 ALERT，仍只能提交告警；RELEASE 令牌只能向发布入口 POST，不能读时间线、提交告警、批准或执行动作。沿用到期、撤销和 namespace 检查。浏览器账号需要 INGEST 权限和 CSRF 校验；只读账号不能接收发布。

请求体上限 16KiB，包括没有 Content-Length 的流式请求。源时间只接受过去三十天至未来一天内的值；与接收时间相差超过五分钟时明确标注可能为迟到、回填或时钟偏差，不自动修正。若同源较早的事件后到，记录乱序边界，并按事件时间/观测区间排序展示。

幂等键为“可信 namespace + 认证来源 + sourceEventId”：

- 首次持久化后返回 201。
- 同键同内容返回 200 和原始事件，包括原接收时间，不重复写审计。
- 同键不同内容返回 409，不覆盖原始事实。
- 存储失败不能返回成功接收；来源可使用同一事件号重试。

## 查询、关联和调查

只读接口：

```text
GET /api/agent/timeline?targetType=ROUTE&target=/orders/checkout&fromMillis=<start>&toMillis=<end>&limit=100
```

targetType 支持 ROUTE、SERVICE、INSTANCE。一次最多七天，limit 为 1～200；实际 HTTP 访问范围仍由当前账号和 Admin 的可信资产范围决定。达到上限保留最近的事件，按时间正序展示，并显式返回 truncated，可缩小时间窗继续调查；历史路由映射最多读取 1000 条，超出时同样标明关联不完整。

发布事件可按源时间或接收时间命中查询。迟到或时钟偏差导致源时间在窗口外、但在窗口内收到的事件仍可见，并单独标注“按接收时间命中”，不能把它认定为在故障窗口内发生。原始申报时间保持不变。

服务事件关联使用持久的历史路由观测，并按最长前缀匹配；不会把现在的服务映射填到过去。配置切换或采集空档覆盖故障时段时，前后关系均可能构成候选，不能把某一边伪装成精确生效历史。同名资源重建保留不同 UID/代次。

全局组件配置作为全局背景显示，资源 subject 标明 component/globalScope，不伪造“这项全局设置只影响本服务”。没有历史映射时仍返回直接匹配的路由事实，并明确无法关联服务发布；不借当前路由强行补齐。

图调查和对话工具统一使用 CHANGE_TIMELINE_QUERY，通过同一个能力执行器形成 EvidenceEnvelope。规则图按明确历史窗口或发布/变更线索将时间线纳入必查；对话支持模型按需调用。指定故障窗口时，Agent 额外向前查询最多十五分钟，避免漏掉告警前的发布；原调查窗口、实际 queryRange 和 lookbackMillis 分别保留。没有指定时间时查询最近一小时。直接 HTTP 查询严格使用给定时间窗。

证据始终为 PARTIAL，不声明完整 coverage；没有事件也不能排除发布或变更。运行时把已采集时间线直接预读给解读模型；模型不需要再猜一个快照工具才能看到发布材料。摘要最多列 20 条，优先发布和变化事件，避免大量基线记录挤掉发布线索；本次查询返回的全部事件仍保留在结构化证据中。事件来源、申报时间、采集时间、记录空档和因果边界同时进入模型上下文。

工作台的实时与历史证据均可展开时间线，展示 UTC 时间、观测区间、来源、事件号、资源身份、发布/制品以及前后变化。外部文字使用 Vue 文本插值，不作为 HTML 执行。

## 迁移与运行边界

Agent schema 升为 v10，使用现有前向迁移机制。旧机器令牌新增 purpose=ALERT 的默认列，权限不自动扩大；已有证据和会话不改写。升级遵循已有停写、备份和重启流程；旧程序不支持 v10 时会拒绝降级启动，不能直接回退旧 JAR 继续写新库。

采集默认沿用 `rover.admin.log-collect-interval-seconds`，当前默认 30 秒，最少 5 秒。周期内变化可能漏采；只在源可读时比较完整快照，不能据轮询声称完整事件流。

本轮提供新事件的可靠接收和历史查询，没有导入第三方平台的既有历史，也没有认证实际生产发布平台。时间线记录独立保存，不随旧 records 的保留任务删除；长期归档、容量边界和保留策略须在 S6 运行认证中落实。它不构成整体生产准入。

## 验收记录

本轮可复用的入口：

```powershell
node deploy/scripts/test_workbench_timeline.cjs
python -m unittest discover -s deploy/scripts -p test_agent_fault_replay.py
python deploy/scripts/agent-fault-replay.py --java D:/java/bin/java.exe --heap-mb 256 --require-evidence-contract --require-change-timeline --require-durable-receipts
```

回放门禁会创建专用模拟发布来源，检查事件幂等、发布令牌不能提交告警、故障前的发布能被路由调查关联，以及测试程序调权后能采集到真实的路由变化。停推仍由 test-harness 执行，不代表 Agent 已实现自动提案、自动修复或效果验证器。

测试记录：

- `.tmp/s2-timeline-agent-verify.log`：common、agent-core、agent-runtime、admin 完整 `verify`，447 项，零失败/错误/跳过。
- `.tmp/s2-timeline-full-verify.log`：未改动的 Nameserver/Gateway 相关模块 142 项通过；该次整体构建在 Agent 旧工具数量断言处失败，不把它写成整次成功。断言与能力清单同步后，上述 447 项完整验证通过。
- 后续补齐源时间偏差时按接收时间命中、发布摘要优先级、原始目标顺序、实例权重和配置严格白名单；`.tmp/s2-timeline-final-v2-verify.log` 对最终源码再执行 26 项专项并重建 Admin JAR，全部通过。专项包含已有回归，不与 447 相加。
- Python 脚本检查 12 项通过：`.tmp/s2-timeline-python-tests-final.log`。Node 使用项目自带 Vue 编译器编译并渲染实时/历史两处模板，验证 UTC、观测区间、制品及文本插值；入口为 `deploy/scripts/test_workbench_timeline.cjs`。浏览器自动化环境启动失败（本机 kernel assets 路径不存在），未完成浏览器视觉验收。
- 最终 JAR 的隔离回放：[result.json](../artifacts/agent-fault-replay/s2-timeline-20261020-02/result.json)。1920 次业务请求、坏版本与超时各重复两轮，证据契约、变更时间线、4 项回执检查和进程清理全部通过。每轮 v2 为 58 次 503/504，停推后的 120 次请求全部成功。各轮 `change-timeline-contract.json` 证明故障前发布关联，`timeline-after-mitigation.json` 保留真实调权观测。
- [最终复核](../artifacts/agent-fault-replay/s2-timeline-20261020-02/validation-review.json)：四源可用、发布进入模型摘要、事件重放不改写、实例权重/临时标记、粘性头、轮询时间边界均通过；当前 JAR 与运行脚本匹配 manifest，测试端口释放，原真实模型配置和主密钥保持不变。

新增回归包含：同一发布重试/冲突及重启、历史最长前缀与当前映射隔离、失败不冒充实例移除、基线恢复、数字 JSON 往返、证据不可改写、范围拒绝、乱序/时钟差、截断、审计失败与事件同事务回滚、基线推进失败回滚、未知配置不入库、URL 凭据脱敏、目标顺序/粘性头/实例权重变化、发布令牌用途/撤销/CSRF 和 16KiB 请求上限。

验证范围：完整回归在最后观测细节修正前执行；最终源码由最后 26 项专项及最新 JAR 故障回放验证。manifest 保存实际二进制和运行脚本摘要。此轮使用关闭模型的确定性调查，没有重跑真实大模型的文本/选工具语义评测；不能把链路成功当作模型诊断质量达标。S2-04～S2-06、S3 自动提案与效果验证仍待后续。
