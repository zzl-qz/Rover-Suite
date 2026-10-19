# Agent 双版本故障与告警回放（S0-06）

入口：[agent-fault-replay.py](../deploy/scripts/agent-fault-replay.py)。测试数据：[scenarios.json](../rover-gateway-test/replay/scenarios.json)。依赖 Java 17+、Python 3.10+；构建需要 Maven。运行器只使用 Python 标准库，可在 Windows/Linux 本机运行，不要求 Docker 或模型密钥。

这套环境用于反复证明“真实请求出了问题 → 告警进入 Agent → 调查证据对应故障时段 → 停推坏版本后抽样请求恢复”。停推由测试程序调用 Gateway 管理接口完成，记录为 `test-harness`，不代表 Agent 已实现自主提案、审批执行或业务效果验证器。

## 1. 启动与验收

在仓库根目录先构建当前源码，避免使用过期 JAR：

```powershell
Set-Location E:\roverSuite
New-Item -ItemType Directory -Force .tmp/nio, .tmp/java | Out-Null
mvn -pl rover-nameserver-bootstrap,rover-gateway-bootstrap,rover-admin,rover-gateway-test/backend -am `
  '-DargLine=-Djdk.net.unixdomain.tmpdir=E:/roverSuite/.tmp/nio -Djava.io.tmpdir=E:/roverSuite/.tmp/java' verify
python deploy/scripts/agent-fault-replay.py
```

Linux 同样在根目录执行 Maven 和 Python；`argLine` 中的临时目录改为本机绝对路径，普通环境可省略该参数。本机 Windows 沙箱的 Java 临时目录与 Unix-domain socket 路径问题需要上述参数。

运行器默认使用 `127.0.0.1:19080～19085`：Nameserver TCP/HTTP、Gateway、v1、v2、Admin。可通过 `--base-port 19180` 使用另一个连续六端口段。端口占用直接失败，不会停止已有服务，也不自动连接已有环境。

S1 单 Admin 运行基础可追加真实 HTTP 验收：

```powershell
python deploy/scripts/agent-fault-replay.py --heap-mb 256 `
  --require-durable-receipts --require-single-admin-foundation
```

新增检查覆盖资源清单、角色分权、机器告警、实时撤权、停写、Admin 重启后令牌/停写持久性、SSE 重放、安全审计和一致性备份，共 13 项；结果写入 `single-admin-probe.json` 与 `result.json.singleAdminChecks`。该运行会额外重启本次创建的 Admin，仍只清理自己的进程。`--heap-mb` 控制每个测试 JVM 的堆上限，范围 128～512 MB；内存紧张时与 Maven 顺序运行。这些检查不代替真实进程强制终止、预算恢复和 ZIP 实际恢复的 Java 测试，也不构成整体生产准入。

S2 证据契约与原生历史窗口检查可追加 `--require-evidence-contract`。它会核对实际故障样本中的版本、实例、UID/代次、授权范围、响应来源、失败环节与历史覆盖；每个场景产生 `evidence-contract.json`，全部通过后设置 `result.json.evidenceContractGatePassed=true`。口径与限制见 [证据契约交付说明](agent-evidence-contract.zh-CN.md)。

显式接入已配置的真实模型（会产生服务商 API 调用费用）：

```powershell
python deploy/scripts/agent-fault-replay.py --heap-mb 256 --require-durable-receipts `
  --model-config E:/roverSuite/config/admin-model.properties `
  --model-master-key-file E:/roverSuite/config/master.key
```

`--model-config` 指向已启用、保存完整的 Rover 加密模型配置；主密钥文件默认取该文件同目录的 `master.key`，也可显式指定。运行器将两者复制到本次隔离目录，在 finally 中清理这两个副本，保留原始配置。密钥不会出现在命令行或报告中。该模式需要允许 Admin 联网访问已配置服务商，仅发送本次模拟服务的证据。

真实模型模式先验证连接，再执行四次故障调查，以及一次由模型实际调用只读工具的交互式调查。告警描述不提供 `bad-version` 等故障答案；同时检查实例证据必须识别出两个健康版本。`AI_EXPLANATION` 成功且解读非空、实际模型调用指标成功、对话取回工具证据、路由未被修改才算通过；规则回退不能替代模型验收。规划与解读的成功、超时、无效输出等分别保留在指标中；`liveModelGatePassed` 要求至少一次模型规划成功和全部故障解读成功，并非承诺每轮规划都成功。模型回答的事实准确性仍须对照原始证据人工复核，流量切换与恢复仍由 `test-harness` 执行。

最后使用本机测试 HTTP 服务模拟模型供应商返回 503，检查任务能保留证据、明确标记 AI 解读失败、保持路由不变。这个服务仅接收专用假密钥。故障探针会修改隔离副本的模型配置，结束时一起清理；原始模型配置不变。结果记录在 `model-failure/` 和 `result.json.modelFailureChecks`，不算作真实服务商调用。

本次实际接入发现的问题、修复与证据见[真实模型故障调查验收](agent-live-model-validation.zh-CN.md)。

每次运行创建新的 `artifacts/agent-fault-replay/<时间-随机ID>/`。用 `--output artifacts/agent-fault-replay/my-run` 指定新的目录也可以；已有目录拒绝复用，以防旧 overlay、账号或结果混入。启动、失败、Ctrl+C 都清理本次持有的子进程；保留证据文件，便于排障。强制杀死运行器本身无法保证执行 finally，手动清理前需核对进程身份与日志，不能仅凭端口或旧 PID 杀进程。

## 2. 固定故障与请求集合

两个测试 backend 以同一服务名 `replay-service`、不同 group `v1/v2` 注册到真实 Nameserver。Gateway 的专用路由 `replay-orders` 初始权重 50/50，请求头 `X-Replay-Key` 用于粘性分流。固定 seed 生成 120 个用户键，每阶段使用完全相同的键；因此重复运行比较的是相同用户的真实分流与响应，不依赖并发请求完成顺序。

| 场景 | 故障注入 | 预期客户端结果 |
| --- | --- | --- |
| bad-version | v2 返回 503；v1 正常 | 原先落到 v2 的用户收到 503，v1 用户仍为 200 |
| upstream-timeout | v2 延迟 1000ms；Gateway 超时 250ms | 原先落到 v2 的用户收到 504，v1 用户仍为 200 |

每个场景默认重复两次，每次四个阶段，每阶段 120 个请求、并发 4：

1. **baseline：** 两版本都正常；必须无错误且两版本都有样本。
2. **fault：** 注入故障；按 baseline 的逐用户分流核对故障状态码，不能以“出现一些错误”蒙混通过。
3. **告警调查：** 发送实际请求窗口的起止时间，等待 Agent 完成，保留完整任务与工作台数据；证据窗口必须一致，必须有真实追踪样本，不能用当前状态排除历史假设。503 场景须确认 H5 观察事实；超时场景不要求现有规则假装能确认 503 或给出根因。
4. **mitigated / restored：** 由测试程序将 v2 权重设为 0，必须观察到 120 个 v1 成功响应；随后清除故障、恢复权重 50，两版本逐用户响应必须回到 baseline。

两次重复的逐用户结果指纹必须相同。延迟不参与相等比较，P95 仅作为本次观测记录。错误率分母是全部发出的请求；网络错误记为状态 0，不能从分母中丢掉。没有样本不能报告恢复。

故障控制接口 `GET/POST /__test/replay` 只存在于测试 backend，默认关闭；必须显式设置 `rover.demo.replay.enabled=true` 和非空测试令牌。控制接口不在 Gateway 业务路由下，每次控制请求检查 `X-Rover-Replay-Token`。只允许状态 200/503 和 0～2000ms 延迟。

## 3. 操作回执专项与 S1-05 门禁

流量回放完成后，再执行真实 Gateway 管理 API 检查：

- 先连续执行两次不同的调权操作，查询第一笔，确认它曾经 APPLIED。
- 用同一个 operationId、不同权重重放，检查是否拒绝参数不匹配且不改变当前路由。
- 重启本次启动的 Gateway，核对路由/revision 恢复，再分别查询第一笔和最新一笔回执。
- 再执行 205 次调权，查询旧操作与最新操作，检查是否存在回执淘汰。

回放是否成功、四项基础回执检查是否通过是两个独立结果。S0 基线的回执仅在内存保留 200 条，本检查保存了其可重现失败样例；S1-05 已增加[事务回执与参数绑定](gateway-operation-receipts.zh-CN.md)，使用严格模式重新验收。下方“实际交付验证”保留 S0 当时结果，不能将 UNKNOWN 当作“没有执行”，也不能把四项基础检查等同整体生产准入。

```powershell
# 作为未来 S1-05 的严格门禁；发现回执不满足条件时返回退出码 2。
python deploy/scripts/agent-fault-replay.py --require-durable-receipts
```

退出码：0 表示 S0 故障回放与清理通过；1 表示回放/启动/检查失败；2 表示故障回放通过但严格回执门禁未通过；130 表示中断。默认运行仍会在 SUMMARY 中明确显示回执门禁结果。

## 4. 证据产物与人工复核

| 文件 | 用途 |
| --- | --- |
| SUMMARY.md / result.json | 总结果、每阶段请求结果、重复性、回执门禁、进程清理情况 |
| manifest.json | 固定参数、请求键、JAR SHA-256、源码提交与本次端口 |
| cases/*/*-requests.jsonl | 每次请求的键、起止时间、状态、版本与耗时 |
| cases/*/*-summary.json | 错误数/总请求数、状态码与版本分布、P95 |
| cases/*/*-metrics.json / *-traces.json | 同时采到的真实 Gateway 窗口指标与抽样追踪 |
| cases/*/alert-input.json / task.json / workspace.json | 实际告警窗口、Agent 证据/假设/限制、处理人可见的工作台数据 |
| receipt-probe.json | 参数重放、重启、205 次操作前后的请求和回执 |
| live-model-config.json / live-model-connection.json | 真实模型模式的生效配置摘要和实际连接结果，不含密钥 |
| cases/*/live-model.json / *-agent-metrics.prom | 每次真实模型解读、步骤、证据数量和模型调用结果指标 |
| conversation/task.json / workspace.json / checks.json | 模型交互调查的工具证据、回答、调用指标及路由未变检查 |
| 各组件 process-*.log | 所有本次启动进程的日志；Gateway 重启保留不同代次日志 |

人工复核至少检查：baseline/fault 使用同一批用户；实际故障状态码匹配；告警窗口覆盖真实请求；调查中历史与当前证据分开；停推后的样本量足够且只有 v1；恢复后两版本回归；回执 UNKNOWN 被列为缺口。

窗口指标可能同时包含多个阶段，不能将其当作某一个阶段的独立错误率。阶段错误率以请求 JSONL 为准。默认全量采样用于可重复实验，不是生产采样配置或性能基准。

测试配置和 H2 均位于本次目录。默认不读取现有工作目录下的运行配置；子进程不继承 Rover/Spring/OpenAI 模型配置环境变量和 Java agent 注入参数，模型保持关闭。只有显式传入 `--model-config` 才复制指定模型凭据并访问真实服务商。本次只创建随机管理令牌与本地测试账号，不使用客户业务数据。`artifacts/` 不入库，分享产物前须排除数据库、备份和运行时配置。

运行器自身的失败分支检查：

```powershell
python -m unittest discover -s deploy/scripts -p test_agent_fault_replay.py -v
```

## 5. 实际交付验证

2026-10-18，在源码基线 `dea0f17` 上完成本批实现与验证：

- Maven `verify` 构建成功，实际运行 **507 项 Java 测试，0 失败、0 错误、0 跳过**；回放脚本的 **7 项 Python 检查通过**。日志为 `.tmp/s0-replay-verify.log`，测试统计为 `.tmp/s0-replay-test-summary.json`。
- [正常模式结果](../artifacts/agent-fault-replay/s0-validation-20261018-04/SUMMARY.md)：退出码 0，四个案例及重复性检查通过，进程清理通过。
- [严格模式结果](../artifacts/agent-fault-replay/s0-final-strict-20261018/SUMMARY.md)：同样完成四个案例和清理，因回执生产门禁未满足，**按预期返回非零结果**（脚本定义为退出码 2）。故障控制无令牌返回 403，经 Gateway 访问控制路径返回 404。
- 每次完整运行产生 **1920 次业务请求**（两个场景 × 两次重复 × 四个流量阶段 × 120），并触发四次真实 Agent 调查。两次完整运行的逐用户结果一致。

| 场景 | 重复次数 | 每轮 baseline | 每轮 fault | 每轮停推后 | 每轮恢复配置后 |
| --- | --- | --- | --- | --- | --- |
| v2 返回 503 | 2 | 120/120 成功，v1=62/v2=58 | 58/120 返回 503，其余成功 | 120/120 成功，全部 v1 | 120/120 成功，v1=62/v2=58 |
| v2 响应超时 | 2 | 120/120 成功，v1=62/v2=58 | 58/120 返回 504，其余成功 | 120/120 成功，全部 v1 | 120/120 成功，v1=62/v2=58 |

503 调查确认了 H5“窗口内观察到 503”，不将其当作根因。504 场景保留 UNKNOWN 与证据边界；当前规则对超时的归因能力仍需在 S2 演进。所有调查均检查实际追踪样本、指定窗口和工作台归属。

回执专项在真实 HTTP API 与真实 Gateway 进程重启中复现了三类 S1-05 失败样例，完整请求和响应在各次运行的 `receipt-probe.json`：

| 检查 | 实际结果 | 后续要求 |
| --- | --- | --- |
| 同一操作号、不同权重 | 返回 REPLAYED；当前路由未改变 | 应拒绝参数不匹配，不能声称是同一请求重放 |
| 连续多次执行后重启 | 路由/revision 恢复；第一笔和最新回执都变为 UNKNOWN | 持久保存并恢复可查询的执行回执 |
| 再执行 205 次操作 | 新操作仍为 APPLIED，旧操作变为 UNKNOWN | 定义持久回执保留与过期语义，不能将淘汰当未执行 |

本次正常模式与严格模式的输出均明确记录 `receiptProductionGatePassed=false`。这些是下一阶段需要修复的产品缺口，不属于已通过的生产能力。

通过 S0-06 表示后续开发有真实、可重复且可核对的实验环境；它不替代 PostgreSQL、持久 Job/租约、资源权限、模型评测、效果验证器和生产观察期。
