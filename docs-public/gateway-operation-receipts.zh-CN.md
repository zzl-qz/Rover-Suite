# Gateway 持久操作回执（S1-05）

Gateway 的路由写入现已将确认配置和操作回执放在同一本机 H2 事务中。它解决请求超时后的对账问题，不代表 Agent 已有自主提案、多节点执行或业务恢复验证。

## 提交与查询

整表替换、单条新增/替换、删除、单目标调权和历史快照回滚都要求显式 `revision` 和 1～200 字符的非空 `operationId`。摘要使用 SHA-256，输入为带版本的动作类型、期望 revision 和结构化动作参数；对象字段顺序固定，路由/目标数组顺序保留，因为顺序可能影响路由与分流。调权摘要绑定 routeId/serviceName/group/weight，不绑定后来发生变化的整张表。现阶段 Gateway 管理面只有单一管理令牌，团队/主体级授权与资产 UID 属于 S1-01/02。

- 同号同参：返回原操作的 `REPLAYED`、原生效 revision 和原路由快照；不会再次修改当前路由。当前状态另读 `GET /_manage/routes`。
- 同号异参，包括动作类型或期望 revision 改变：409，`code=OPERATION_PARAMETER_MISMATCH`，原回执不被覆盖。
- revision 冲突或路由校验失败：保存 `CONFLICT`/`REJECTED` 回执。修改请求需要重新规划并使用新的操作号，原操作不能通过重试刷新版本。
- `GET /_manage/routes/operations/{operationId}`：返回状态、原 revision、`requestHash`、`atMillis`、`expiresAtMillis` 和 `currentRevision`。
- 完整回执的可用期为提交后的 **30 天**，到期返回 `EXPIRED`，重放返回 409 `OPERATION_EXPIRED`。操作号与摘要不按条数淘汰，也不允许复用；本批尚未自动压缩过期快照，磁盘容量需要监控。这是当前实现期限，生产部署必须确认它覆盖实际重试/离线恢复窗口。
- `UNKNOWN`、`EXPIRED`、存储不可读均不能证明未执行，不能换号补做。Agent 遇到 5xx 或未知响应先用原操作号回查；过期回执保持 `UNCERTAIN`。

调权 API 的 revision、weight 及回滚 API 的 toRevision 必须是精确的 32 位整数。缺失、非法字符串、小数和溢出不能变成合法权重 0。

## 文件与提交顺序

默认存储位于 Gateway 工作目录的 `config/`：

| 文件 | 作用 |
| --- | --- |
| `routes.overlay.json.state.mv.db` | 事务库，保存当前路由、revision、存储代次及操作回执 |
| `routes.overlay.json.state.fence.json` | 存储代次、revision 与操作数的持久高水位见证 |
| `routes.overlay.json` | 升级前的 JSON；事务库存在后不再作为事实来源或自动更新 |

提交顺序：验证原操作/参数与 revision → 校验、预构建 → 同一 DB 事务写入回执和路由 → 刷盘并原子替换见证 → 提交事务 → 发布运行态。H2 使用 `WRITE_DELAY=0`，提交等待日志刷盘；见证文件使用 `FileChannel.force(true)` 和同目录原子替换，无法原子替换即拒绝写入。

这两个文件不是分开提交的两份路由事实：路由和回执只认事务库。见证提前记录高水位，故提交前崩溃可能让见证领先于 DB；此时拒绝启动，要求人工对账，不能悄悄回退 YAML。已提交后崩溃则从同一事务恢复路由和回执。物理断电时的最终持久性仍受文件系统/磁盘保证限制，本批真实进程崩溃测试不能替代物理断电认证。

存储写入失败时不发布候选路由；不能用仅在内存中保存的 `FAILED` 伪装持久事实。能读到的已提交回执继续用于对账；如果存储本身不可读或见证不一致，查询也必须失败，不能返回“没有执行”。

这是 **单 Gateway 本机存储**，不能让多个 Gateway 共享同一路径，也不能替代 Agent 自身的持久任务与恢复机制。按 2026-10-18 调整后的计划，S1-03/04 先沿用 Admin 的文件 H2 补齐单进程持久运行，共享 PostgreSQL 和跨进程协作后置 S7。当前 Gateway 每次操作短连接读写并同步刷盘，尚未做写吞吐/容量认证。

## 升级、备份与故障处理

1. 停止新的管理写入，先备份现有 `config/`，部署后先只读核对路由/revision。
2. 没有事务库时仍可读取旧 JSON。首次新操作建立事务库并导入当前路由。旧 JSON 只含最新操作号，无法推断完整回执或参数；已知的旧操作号保存为 `UNKNOWN` 墓碑并拒绝复用，更早的旧操作保持未知。
3. 一旦有事务库，重启优先恢复它。数据库损坏、缺少见证、仅恢复了旧 DB 或存储代次不匹配时停止启动/写入；不会使用旧 JSON 或 YAML 冒充最新状态。`adminEnabled=false` 仍保持只认 YAML 的既有部署语义。
4. 做离线备份时停止 Gateway，保存数据库和见证为同一组，并归档未完成操作号、当前路由/revision及备份时间。不要只复制运行中的 MVStore 文件。
5. 恢复备份必须先关闭管理写入，人工核对备份之后发生的操作、当前预期路由及所有未确认执行。整组 DB 和见证同时回退无法仅靠本机文件检测；需要外部审计和新资源代次（S1-01/06）完成生产恢复协议，不能把一对旧文件当作“没有后续执行”的证明。
6. 见证领先/数据库损坏时保留全部文件和日志，在隔离目录检查可用备份并逐项对账，再决定恢复哪一份可信状态。不要删除见证、删除数据库或伪造 revision 来解锁，不要在旧状态上自动续发 Agent 写请求。本批未提供自动修复见证/备份恢复工具。

`config/`、数据库和运行日志均由仓库忽略规则排除。公开文档与测试源码应提交。

## 验收

```powershell
$env:MAVEN_OPTS='-Xmx384m -XX:ActiveProcessorCount=2'
mvn -pl rover-nameserver-bootstrap,rover-gateway-bootstrap,rover-admin,rover-gateway-test/backend -am `
  '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2 -Djdk.net.unixdomain.tmpdir=E:/roverSuite/.tmp/nio -Djava.io.tmpdir=E:/roverSuite/.tmp/java' verify
python deploy/scripts/agent-fault-replay.py --require-durable-receipts
```

重点检查：超过 200 次操作后旧回执可查；同 ID 异参拒绝且路由不变；人工变更/删除后重放返回原结果；重启保留旧与最新回执；30 天到期拒绝重放；实际子进程在事务提交前后强退的恢复行为；写入失败不发布路由；损坏数据库不回退；旧 DB 恢复被高水位发现；旧 JSON 不伪造回执；Agent 在 5xx/EXPIRED 时保留原号且不新增写入。

故障存储测试使用阻塞见证临时路径制造真实文件 I/O 失败，并在真实 DB 事务提交点注入“磁盘满”SQLException 检查回滚/停写；没有填满宿主机磁盘。实际磁盘满、物理断电、整组旧备份恢复及多节点权限/租约仍需后续生产演练。S0 回放脚本中的 `receiptProductionGatePassed` 仅表示四项基础回执检查通过，不能代替整体生产验收。

## 本批验证记录（2026-10-18）

- 源码基线 `5643506`，本批改动未提交。完整 Maven `verify` 实际运行 **521 项测试，0 失败/错误/跳过**；日志 `.tmp/s1-receipts-verify.log`，逐模块统计 `.tmp/s1-receipts-test-summary.json`。随后新增的事务提交处磁盘满注入 **1 项定向检查通过**，日志 `.tmp/s1-receipts-disk-full-test.log`，不把它混算为同一次完整构建。
- [严格回放结果](../artifacts/agent-fault-replay/s1-receipts-20261018-01/SUMMARY.md)：进程退出码 0，1920 次实际业务请求、四次真实 Agent 调查、重复性、故障控制鉴权及进程清理均通过。模型关闭，停推仍由 test-harness 发起。
- [真实回执证据](../artifacts/agent-fault-replay/s1-receipts-20261018-01/receipt-probe.json)：同号异参返回 409；第一笔 revision=9、最新 revision=10 均在重启后仍为 APPLIED，摘要未改变；连续 205 次后旧回执 revision=11 仍为 APPLIED，当时当前路由 revision=216。
- 上一轮失败的四项基础回执检查本轮全部转绿。回放产物位于本机被忽略的 `artifacts/`，源码、场景和验收命令随仓库发布以便复现。

本记录是 S1-05 开发环境交付证据；整体仍未通过生产验收。
