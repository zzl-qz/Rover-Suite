# S2-05：外部只读遥测接入

日期：2026-10-20（Asia/Shanghai）。现有调查图与工具对话现在可以读取 Prometheus 应用指标、Loki 应用日志和 Tempo 链路摘要，沿用证据、任务、H2 与工作台流程。默认不配置外部源，不修改现有模型密钥或部署环境。

## 调查流程

新增 `APPLICATION_METRICS_QUERY`、`APPLICATION_LOG_QUERY`、`APPLICATION_TRACE_QUERY`。规划只开放已配置源对应的能力，确定性规划将其列入补查项。对话工具为 `getApplicationMetrics/Logs/Traces`，没有 URL、查询语句、环境或服务名参数，使用本次任务目标；未配置时返回证据缺失。

外部读取经过 `CapabilityExecutor` 的实时授权、共享工具预算和重复查询限制；撤权、会话失效或管理范围变化先拒绝。分页另受总读取时限、页数、行数和响应大小限制。新数据仍是来源观测，不能自动确认模型病因，也不会获得审批或动作权限。

## 配置

见 [追加配置示例](../deploy/production/agent-telemetry.example.yml)。复制需要的源到受保护的本机配置文件，通过 `spring.config.additional-location` 追加。没有 `rover.agent.telemetry.sources` 时新规划能力均不开放。

每个源必须配置：

- `id/kind/endpoint/allowed-addresses`。地址只允许 HTTPS origin，不接受路径、用户信息、查询或 fragment；HTTP 需显式 `allow-http: true`。使用路径代理的平台需提供独立 origin。域名实际 IP 必须在 1～16 个明确允许的 IP 中。
- `scope.organization-id/team-id/environment-id`、现有 `control-target-id`、外部应用的 `namespace`。必须与任务范围匹配；已有部署不要更改管理目标 ID。旧部署没有显式 ID 时，先从现有资源身份读取实际 ID，再写入源配置。
- `bindings` 将目标映射到外部服务名。SERVICE、INSTANCE 精确匹配；ROUTE 匹配自身及 `/` 分隔的子路径，取最长前缀。`/orders` 不匹配 `/orders-foreign`。同范围同能力有多个源同时命中时拒绝，不隐式扩大读取。
- Prometheus `metrics` 必须明确列出 1～8 个指标名。Loki、Tempo 的可选 `tenant-id` 固定为单一租户，不支持 `a|b` 联邦读取。凭证只经运维配置，使用平台只读身份和受保护环境变量。

`namespace` 是外部数据集标签。Admin 内部的资源命名空间由宿主单独注入，不与应用命名空间混用；旧任务不能借连接器访问变更后的内部范围。

模板始终精确过滤组织、团队、环境、应用命名空间、服务。默认标签为 `organization_id`、`team_id`、`environment`、`namespace`、`service_name`；Tempo 对应 resource attributes，服务使用 `resource.service.name`。这些标签必须由采集管道或平台建立，代码不会为无范围的数据补造标签。

可在每个源的 `labels` 完整配置 `organization/team/environment/namespace/service` 五个标签名，不得重名。缺少范围标签会查不到数据；Prometheus/Loki 返回范围不匹配的数据会拒绝。Tempo 查询匹配 span 的 resource 条件，返回 trace 根服务可以不同于目标服务，不能将摘要当作目标服务全量流量。

## 协议与证据

| 源 | 固定接口 | 语义与限制 |
| --- | --- | --- |
| Prometheus | `GET /api/v1/query_range` | 指标白名单与五类精确标签；数值矩阵含时间、值和标签。步长至少 30 秒并随预算调整。非有限值不当零；counter 原值不当窗口请求数，不自行计算错误率或 p99 |
| Loki | `GET /loki/api/v1/query_range` | 固定 selector、forward 查询；毫秒窗口转换纳秒。分页重叠边界并去重，同一时间戳无法前进或预算耗尽时明确截断；不跳过一纳秒假装完整。完全相同的日志行不可区分，返回条数不是请求数 |
| Tempo | `GET /api/search` | 固定范围 TraceQL；仅保留 trace ID、根服务、操作、时间与耗时摘要。搜索没有通用续读游标，达到上限即截断；不下载整条任意 trace，不宣称全量覆盖 |

协议依据：[Prometheus API](https://prometheus.io/docs/prometheus/latest/querying/api/)、[Loki API](https://grafana.com/docs/loki/latest/reference/loki-http-api/)、[Tempo API](https://grafana.com/docs/tempo/latest/api_docs/)。文档核对和本地协议测试不能替代特定部署版本的现场联调。

`EvidenceEnvelope` 保留源 ID、查询 ID、模板版本 `telemetry-scope-v1`、任务上下文、请求/事件窗口、取证时间、观测条数、脱敏记录和局限。引用不含内部地址、凭证或完整查询 URL。三类外部数据不冒充 Gateway 原生统计，不自动升级现有业务假设。

有记录但覆盖不明为 `PARTIAL`，成功无样本为 `EMPTY`，读取失败为 `UNAVAILABLE`，超出窗口限制为 `OUT_OF_RANGE`，近期窗口观测过旧为 `STALE`。未知覆盖时 `coverageRange=null`，历史查询不会因为距今天较远就被误判过期。空结果不能证明服务健康。

## 预算与保护

默认每次能力总时限 15 秒、最大查询窗口 1 小时、最多 150 条、每页 50 条、最多 3 页、每页 1 MiB、新鲜度阈值 120 秒、连接器并发 4 次。这是可配置边界，不是 SLO。更宽窗口直接拒绝，不悄悄裁剪；读取时限取配置与任务剩余时间的较小值，分页不重新计时。配置 `limits` 时完整提供示例中的七个值，越界启动失败。

HTTPS 使用系统证书与主机名校验。连接时由受控 DNS resolver 检查实际 IP，关闭跳转、系统代理继承、Cookie、压缩和自动重试；禁止云元数据、链路本地、组播和未指定地址，即使它们被写进允许列表。DNS 后台最多四个有界线程，OS 解析不可中断时调用线程仍按预算返回，积压直接拒绝；部署仍应配置网络出口策略。

为在实际连接时约束 DNS 地址，新增 Apache HttpClient 5，版本由现有 Spring Boot BOM 管理，本轮解析为 5.6.4。没有引入 PG、外部 Agent 进程或观测平台 SDK。

脱敏在摘要、模型输入及证据落库前执行：移除配置中的已知凭证、常见 password/token/Authorization/Cookie/API key 值与私钥片段，限制输出标签和单条文本长度。版本为 `telemetry-secrets-v1`；它不是完整的个人信息或任意秘密识别系统，源数据本身仍需有访问和脱敏规则。错误响应体、原始异常和请求 URL 不进入模型或证据。日志中的指令性文字统一套不可信数据围栏，不产生动作权限。

## 验收与后续

最终源码执行 `mvn -o -pl rover-admin -am verify`，common 20、core 118、runtime 262、admin 121，合计 **521 项通过**，零失败、错误或跳过，打包成功。日志为 `.tmp/s2-telemetry-release-verify.log`；本轮新增 40 项检查（连接器 35、配置 4、工作台 1），没有将旧测试重复计为新增。

真实回环 HTTP 检查包括：Prometheus 窗口、标签与数值口径；Loki 重叠分页、相同时间戳截断、密码/多 Cookie/私钥与已知凭证脱敏；Tempo 固定 TraceQL、根服务区别与窗口外摘要排除；组织、团队、环境、内部命名空间、管理目标和资源越界；撤权、歧义映射、元数据与未允许 DNS 地址；重定向、慢响应、过大/错误响应和任务预算耗尽；EMPTY、STALE 与无效数值警告有界。

工作台集成测试走已登录且含 CSRF 的入口，创建真实会话和任务，经真实权限、宿主命名空间、H2、三个回环 HTTP API 读取，再通过任务查询拿到三份带范围和质量的脱敏证据；未登录查询拒绝。模型选择使用脚本，没有调用真实供应商，也不冒充模型诊断质量评测。之前两轮完整回归分别为 516、520 项通过，随后补齐边界后以本次 521 项为最终结果。

验收范围为单 Admin + H2；本轮完成 S2-05 开发交付，当前主线 **18/42、S2 5/6**。生产准入仍按 S2～S6 门禁推进。

本轮没有生产平台 URL、只读凭证及索引配置，不能声称已连接生产 Prometheus/Loki/Tempo。上线前需用固定资源与时间窗核对平台原始结果、拒绝路径、采样/索引边界和凭证轮换。

本轮没有数据库 Schema 迁移，旧任务和证据继续可读。启用后新任务会保存新增的能力枚举；旧二进制不理解这些新值，因此回退前需排空任务并按现有备份/兼容策略处理，不能把“无 Schema 变化”理解为可直接用旧程序读取全部新任务。

下一项 S2-06 继续模型/提示词/模板版本、供应商隔离、持久预算与代表性离线评测；自动处置提案、审批和业务效果验证按 S3 推进。
