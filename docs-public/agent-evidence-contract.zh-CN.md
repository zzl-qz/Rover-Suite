# S2-01 / S2-02：证据契约与最终响应观测

日期：2026-10-19。部署方式继续采用单 Admin + H2。

这轮解决的是“Agent 拿到的事实是否足够清楚”。例如，同样是 HTTP 504，现在可以分清后端实际返回 504，还是 Gateway 等待后端超时后生成 504；每条新追踪保留请求当时的版本、实例、路由 UID 和代次，不使用当前路由身份补写过去。

## 已实现的契约

`Evidence` 保留旧字段，新增 `envelope`。图调查和对话工具经同一 `CapabilityExecutor` 装配 `InvestigationContext`，并使用同一证据表述向模型提供质量边界。

| 字段 | 含义 |
| --- | --- |
| schemaVersion、sourceId/sourceKind、sourceQueryId、queryTemplateVersion | 数据契约和查询来源，不包含管理凭证 |
| requestedRange、eventTimeRange、coverageRange、collectedAtMillis | 请求时段、事件时段、实际覆盖和取证时刻；缺失信息保留未知 |
| qualityStatus、samplingMode、sampleCount、limitations | 可用性、采样、样本数和不可据此判断的范围 |
| context | 服务端任务、事件、授权范围、管理目标、查询目标、预算快照和授权策略版本 |
| resourceRefs | 有原始 UID/代次时才生成带范围的资源引用；追踪引用使用请求时身份 |
| facts、payloadDigest | 不可变 JSON 事实及稳定 SHA-256 摘要，持久化读取时校验摘要 |
| sourceRevision、validUntilMillis | 源未提供时保持 null，不以取证时刻伪造历史版本或有效期 |

质量分为 AVAILABLE、EMPTY、PARTIAL、UNAVAILABLE、STALE、OUT_OF_RANGE。EMPTY 表示确有覆盖但没有请求；取不到数据不能写成 EMPTY。抽样追踪始终不能证明完整窗口；当前配置与健康实例不能证明指定历史时段没有故障。

H2 沿用既有 JSON 列保存版本化载荷，无需新增数据库。旧 metadata 格式仍可读取，映射为 PARTIAL，保留原 ID 和内容，不补造历史覆盖、UID 或授权上下文。任务证据只允许按顺序追加；修正事实需要新证据 ID，禁止覆盖原证据。

工作台的调查卡与历史证据列表显示质量、零样本、实际覆盖和资源身份，可展开查看事实、来源及判断边界。

## 请求与指标口径

追踪新增 `observation`：请求当时的路由 UID/generation、serviceName/group/hostPort、responseOrigin、failurePhase、rejectReason、responseComplete 以及逐次 attempts。

- responseOrigin 来自真实执行路径：UPSTREAM / GATEWAY / UNKNOWN，不能根据状态码推断。
- CONNECT / CONNECT_TIMEOUT、UPSTREAM_TIMEOUT、UPSTREAM_IO、TARGET、REQUEST_BODY、UPSTREAM_RESPONSE_LIMIT 等分别记录。连接建立后断开不再冒充连接建立失败，也不能借此触发连接失败重试。
- 响应头已发出后出错，保留已发出的状态码并标记未完成，不能把一个已开始的 200 改记成客户端收到的 502。
- `responseComplete` 描述代理读取完成或本地响应已提交写回，不承诺客户端已经完整接收；延迟是 Gateway 处理耗时，不是客户端端到端延迟。

原生 `routeWindow` 独立于 trace 抽样，按请求完成时间计数。一次请求即使转发两次，也只增加一次 finalRequests；attemptCount 和 attemptFailurePhases 单独计数。最终 5xx、后端原始 5xx、Gateway 生成 5xx、未完成响应、拒绝原因分别保留，不把相互重叠的分类相加当作总请求数。

业务过滤链中的短路、整机并发过载与同连接并发拒绝进入最终计数。路由匹配前拒绝归入 `__unmatched__`，不能编造所影响的具体路由。HTTP 解码之前的传输错误不属于该业务请求窗口。

窗口按请求当时的路由 UID/代次、服务、版本、实例分别聚合。同一个 host:port 被不同版本使用时不会在新窗口中合并。旧端点聚合若出现混合版本会显式标记未知，不再沿用首次观测到的版本。

`routeWindow.versions` 按资源代次、服务和版本合并原始计数及直方图，实例行仍单独保留。追踪另存 `facts.samplePopulations`：程序计算各身份的 sampleRequests、sample5xx 和 sample5xxRate。它们的分母只来自同一个资源的保留样本，不能拿 v1 的请求数作 v2 的分母，也不能据此宣称完整流量错误率。已采集的这些观测由运行时直接放入模型上下文，不依赖模型主动选择某个快照工具。

耗时使用原始请求直方图合并，返回桶边界、桶计数和样本数；`p95UpperBoundMillis` 是直方图桶上界近似值，不能当作精确 P95。旧 byVersion.p95Millis 保留兼容，同时标注 `MAX_INSTANCE_RESERVOIR_P95_LEGACY`；旧版本拒绝数标注 LIFETIME。不能将这些旧字段当作新窗口分位数或窗口拒绝计数。

## 查询与边界

通过现有受认证 Gateway 管理口读取：

```text
GET /_manage/metrics/routes?routeId=<id>&range=60
GET /_manage/metrics/routes?routeId=<id>&fromMillis=<start>&toMillis=<end>
GET /_manage/traces?path=<path>
```

默认窗口支持 60/300 秒。历史查询最长 300 秒，只返回保留期及本次采集启动之后、完全落在请求区间内的秒槽，边界不足一秒明确标为 PARTIAL；完全没有覆盖则为 OUT_OF_RANGE，错误率为 null。不会退回当前实时指标回答历史问题。

固定保留 300 个秒槽，每秒最多 256 个资源维度，详情最多 256 行。超额请求明确记录 droppedDimensionRequests，计数标注为下界并抑制完整错误率；维度详情截断同样暴露边界。停用再启用指标会开启新的覆盖区间，不能把中间未采集时段视作零请求。

窗口计数与直方图在每秒槽内一致读取；它不是跨秒、跨节点的事务快照。Gateway 重启会丢失内存窗口，已经交给 Agent 的证据仍在 H2 中。更长历史和跨源时间线由后续 S2-03/S2-05 接入。

实例目前以事件当时的 service/group/host:port 表示；源未提供稳定实例 UID，不能仅凭地址相同认定是同一个实例。

## 验收方式

构建 Gateway、Nameserver 与 Admin，并执行相关模块的完整测试：

```powershell
$env:JAVA_HOME = 'D:\java'
$env:MAVEN_OPTS = '-Xmx384m -XX:ActiveProcessorCount=2'
& 'D:\Meven\apache-maven-3.9.6-bin\apache-maven-3.9.6\bin\mvn.cmd' `
  -pl rover-admin,rover-gateway-bootstrap,rover-nameserver-bootstrap -am `
  '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2 -Djdk.net.unixdomain.tmpdir=E:/roverSuite/.tmp/nio -Djava.io.tmpdir=E:/roverSuite/.tmp/java' verify
```

使用重建的 JAR 运行隔离故障回放：

```powershell
& 'C:\Users\zhuangziliang\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' `
  deploy/scripts/agent-fault-replay.py --java D:\java\bin\java.exe --heap-mb 256 `
  --output artifacts/agent-fault-replay/<new-directory> --require-durable-receipts --require-evidence-contract
```

新门禁检查实际告警证据中的 v2、实例地址、请求时 UID/代次、可信范围、响应来源和失败环节，并核对 Gateway 历史窗口没有混入区间外请求。极短故障没有完整秒槽时允许 OUT_OF_RANGE，但不能给出“零错误率”；抽样追踪仍保留实际失败事实。

新增固定反例覆盖：同状态码不同来源、重试后成功、同端点不同版本和资源重建、旧故障滑出窗口、启动覆盖不足、缺数据、直方图合并、并发计数、维度容量、迟到写入、历史边界、响应中途断开，以及 H2 重启、旧格式读取和证据改写拒绝。

本轮验证记录：

- 相关模块完整 `verify`：572 项 Java 测试，0 失败/错误/跳过；日志 `.tmp/s2-full-verify.log`。
- 后续补齐过滤器异常收尾、未写响应收尾、停采后历史追踪质量、程序分组统计与故障观测预读；当前源码再跑 64 项专项并重建 Gateway/Admin JAR，全部通过。日志 `.tmp/s2-denominator-verify.log`。专项含已有回归，不与 572 相加。
- Python 检查 11 项通过；工作台 JavaScript 语法、零样本/历史无覆盖表述和两处取证详情入口检查通过。
- 无模型完整回放：[结果](../artifacts/agent-fault-replay/s2-evidence-20261019-01/result.json)，1920 次业务请求、4 轮场景、证据契约门禁、4 项回执检查与进程清理全部通过。每轮故障均为 v2 的 58 次失败，停推后的 120 次请求全成功。

二进制边界：572 项完整验证和无模型完整回放在后续异常分支与统计预读修正之前完成；最后的当前源码由 64 项专项及下面的最新 JAR 模型专项验证。构建日志与各次回放的 manifest 记录实际 JAR 摘要，不能将一次测试结果归给另一份二进制。

- 分母修正前的 [真实模型回放](../artifacts/agent-fault-replay/s2-live-glm46-20261019-01/result.json)：960 次业务请求、坏版本重复两轮，规划成功 2 次、解读成功 2 次、工具对话成功 1 次；契约、回执、供应商失败收尾与清理通过。接口门禁通过，但诊断原文存在下面记录的计算错误。
- 修正后的 [真实模型专项](../artifacts/agent-fault-replay/s2-denominator-glm46-20261019-01/focused-validation.json)：最新 JAR、480 次请求，程序给出 v2 为 58/58、sample5xxRate=1；实际 GLM 解读正确引用该分母，明确业务根因尚未确认并标注 UTC。该轮规划发生 1 次超时，规则回退后实际模型解读完成；不能计作模型规划成功。
- 最后专项只验证单个坏版本场景，未重复整套双场景/回执/对话门禁；其 result 中未执行的完整门禁保持 false。完整回放与专项范围分开记录，均已清理测试进程和凭证副本。

第二轮实际模型验收确实发现过错误：模型把 v1 的 62 个请求作为 v2 的分母，写成 58/62≈93.5%；真实 v2 样本是 58 次请求、58 次 503。随后补齐上述程序分组统计与运行时预读，收紧模型对业务根因、样本口径和时区的表述；当前源码追加 64 项专项通过并重建 JAR（`.tmp/s2-denominator-verify.log`）。此前模型原文保留在验收产物中，不能将模型调用成功当成诊断准确。

仍保留诊断质量边界：最后解读仍有把保留样本泛称为“所有请求”的措辞，并写出无可信当前权重依据的“v2 权重 0→0”建议。这个文本不是可执行计划，测试中由独立 harness 调权。`diagnosticQualityAcceptedForProduction=false`；S2-04 的证据引用/结论校验和 S3 的结构化计划、实时参数校验仍必须完成，不能直接执行模型自由文本。

## 后续范围

这轮不代表 S2 整体完成。发布/配置/实例统一时间线、结构化候选病因与反证、按缺失证据继续调查、外部监控适配和系统性模型质量评测仍按 S2-03～S2-06 推进。自动提案和执行后业务效果验证属于 S3。当前交付不能作为扩大自动处置权限的依据。
