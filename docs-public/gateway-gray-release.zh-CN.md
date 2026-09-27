# Gateway 版本化灰度发布

[English](./gateway-gray-release.md) · [文档索引](./README.md)

本文讲版本化路由（`targets`）、安全变更接口，以及支撑灰度的版本维度指标。路由写法与校验规则另见
[配置项参考：路由字段](./configuration-reference.zh-CN.md#路由字段)，管理端点另见
[使用指南：管理接口与 Admin](./user-guide.zh-CN.md#7-管理接口与-admin)。

## 1. 路由模型

一条路由的上游二选一：

- **静态** —— `targetUrl` 或 `targetUrls`；
- **版本化** —— `targets: [{serviceName, group, weight}]`。

两者在同一条路由上**互斥**，旧的扁平 `serviceName` / `group` 字段已删除。`group` 就是版本号
（`v1`、`v2`、…），没有单独的版本字段——注册中心本来就按 group 过滤，查询响应里也已经带
`revision` / `epoch`。

```yaml
routes:
  - id: order-api
    businessPrefix: /api/orders
    targets:
      - serviceName: order-service
        group: v1
        weight: 95
      - serviceName: order-service
        group: v2
        weight: 5
    stripPrefix: /api
```

校验规则（见 `RouteValidator`）：

- 每个 target 的 `serviceName` 非空；
- 同一条路由的所有 target 必须**同一个** `serviceName`——版本化路由是「同一服务的多个版本」，
  不是通用的多服务聚合，这样按版本看指标、按版本回滚才有单一语义；
- `weight ∈ 0~10000`；`weight: 0` 表示「注册但不接流」，这是暂停一个版本而不删掉它的方式；
- `(serviceName, group)` 不重复；
- 权重之和必须**大于 0**。

可选的 `stickyHeader` 必须是合法的 HTTP 头名（`[A-Za-z0-9-]+`）。

## 2. 版本怎么选

版本化路由分两级选择：先选版本，再在该版本内选实例。选版本用的是**连续加权区间**：

```text
slot = floorMod(fnv1a64(stickyKey + "|" + routeKey), 总权重)
```

按 `targets` 的声明顺序累加权重，`slot` 落在哪个区间就选哪个 target。`weight: 0` 的 target 不占任何区间，
因此永远不接流。

### 为什么用连续区间，而不是一致性哈希环

在环上放 `weight` 个虚拟节点时，节点总数就等于总权重（典型只有 100 上下），比例误差受节点数限制，
**无法精确表达 5% 这类小比例**。连续区间是精确比例，且是纯函数：`O(N)` 一次遍历，不用预计算环，
也不用跨层传递持有者对象。代价是失去一致性哈希「成员变更只重映射 1/N」的性质——本阶段不需要它，
因为放量是权重渐进调整、总权重通常不变。

### 放量是单调的

把权重从 `v1` 挪给紧邻其后的 `v2`（例如 `95/5 → 80/20`，总权重仍为 `100`）时，只会让 `v2` 的左边界左移，
右边界不动。于是原先落 `v2` 的键**一个都不会被打回 `v1`**——已经在灰度里的用户不会被换走，canary 的实验
连续性得以成立。

### 哈希与粘性键

选版本用 **FNV-1a 64 位**，不用 `String.hashCode()`。后者只差一个字符就会聚集（`"k#0"` 与 `"k#1"` 只差 1），
拿它分流会破坏比例。哈希里还混入了 `routeKey` 作盐，按路由隔离，避免同一批用户在所有服务上同时踩到灰度版本。

粘性键先取配置的 `stickyHeader` 请求头，缺失则回退客户端 IP；两者都为空时（极少见）退化为按权重随机。

### 失败不跨版本

- 重试与熔断换台**只在同一版本内**进行；换台绝不跨版本，否则会污染灰度比例。
- 某个版本的 `group` **没有健康实例**时，请求严格返回 `503`（`REJECT_NO_UPSTREAM`），绝不改投另一个版本。
  服务发现已收紧为只返回 `isHealthy()` 的实例，并删除了旧的「全不健康就退回全部缓存」分支。
- `weight: 0` 表示「按权重分不到」，不是「没有可接流实例」，**不会**产生 503；于是「分不到流量」与
  「没有实例可接」在指标上是可区分的两件事。

## 3. 安全变更接口

所有写操作都走同一条乐观锁协议。**所有写路径都必须带 `revision`**，没有绕过乐观锁的后门。

| 端点 | 方法 | 作用 |
| :--- | :--- | :--- |
| `/_manage/routes` | `GET` | 路由表 + `revision` + `appliedOperationId` |
| `/_manage/routes` | `PUT` | 整表替换：body `{revision, operationId, routes}` |
| `/_manage/routes` | `POST` | 新增或替换单条：body `{revision, operationId, route}` |
| `/_manage/routes?id=\|businessPrefix=` | `DELETE` | 删除单条：query `revision`，可选 `operationId` |
| `/_manage/routes/preview` | `POST` | 只回逐条差异（`ADDED` / `REMOVED` / `MODIFIED`）与校验结果，不落盘、不生效 |
| `/_manage/routes/targets/weight` | `POST` | 放量 / 停推一个版本：`{revision, operationId, routeId, serviceName, group, weight}` |
| `/_manage/routes/rollback` | `POST` | 回滚：`{revision, operationId, toRevision}` |
| `/_manage/routes/operations/{operationId}` | `GET` | 请求超时后确认是否已执行 |

### 应用协议（写优先 + 预构建）

```
1. operationId 已 APPLIED → 直接返回既有结果（幂等，不再生效）
2. expectedRevision != 当前 revision → 记 CONFLICT，抛 409（details 带 currentRevision）
3. 校验候选路由 → 失败记 REJECTED，抛 400
4. 预构建可运行态（新 RouteMatcher、新 filters）
5. 先落盘（含 nextRevision / operationId）
6. 再原子替换内存引用
7. 更新 revision、记录 APPLIED
```

所有「可能失败的步骤」都在落盘**之前**；落盘之后只剩不会失败的原子引用替换。于是「写盘成功但内存没换」
这个窗口被**结构性消除**，而不是靠 try/catch 兜。反过来，若先改内存再落盘，落盘失败就会出现「内存新、磁盘旧」，
重启后行为倒退。

### 落盘

路由落盘到 `config/routes.overlay.json`，格式为
`{revision, appliedOperationId, appliedAtMillis, routes:[...]}`，用「同目录 `.tmp` + 原子移动」写入。
启动时读回 `revision` / `appliedOperationId` 对齐内存版本号，因此重启后报出的版本就是重启前确认过的版本。

### 冲突、幂等、回滚与确认

- `revision` 过期返回 HTTP **409**，响应体带 `currentRevision`；调用方刷新后重试。Admin 路由页已按此自动刷新并提示。
- 重复提交一个已 `APPLIED` 的 `operationId` 会返回既有结果，不再生效一次。
- 回滚带 `toRevision`，走**同一条**变更协议，产生一个新 revision。因此「回滚不会覆盖别人的新修改」由乐观锁
  天然保证。内存只保留最近 `5` 次已应用快照，窗口外的目标用 `400` 明确拒绝。
- `GET /_manage/routes/operations/{operationId}` 回答「我超时的这次写到底执行了没有」：状态
  `APPLIED` / `CONFLICT` / `REJECTED` / `UNKNOWN`，并回带 `currentRevision`。**`UNKNOWN` 表示没有记录**——
  可能从未提交，也可能已被有界的操作表（最近 `200` 条）淘汰；它**不等于**没有执行。

## 4. 版本可见性

两个只读端点让调用方不必猜就能对账版本：

- Nameserver `GET /_manage/instances/snapshot` —— 按 `service+group` 归并，带 `revision` / `epoch`，
  以及每个实例的 `instanceId`、`host`、`port`、`group`、`weight`、`healthy`、`ephemeral`、`lastHeartbeatMillis`。
- Gateway `GET /_manage/discovery/snapshot` —— 网关**自己观察到的**
  `(serviceName, group, revision, epoch, instanceCount, healthyCount)`。静态发现时如实返回
  `supported=false` 与空列表，不抛异常，于是「没有这项能力」与「读取失败」是可区分的两件事。

对比两者即可回答「我改了配置，网关是不是还没收到推送」。

## 5. 版本维度指标

`GET /_manage/metrics/routes?routeId=..&range=60|300` 新增：

- `targets` —— 配置声明的版本清单（`serviceName`、`group`、`weight`、`label`、`clusterKey`）。
- `byVersion` —— 每个版本：`windowRequests`、`status5xx`、`connectFail`、`timeout`、`avgMillis`、
  `p95Millis`、`noUpstreamRejects`、`circuitOpenRejects`、`sampleSize`、`sufficient`、`errorRate`、
  `capacityProblem`。
- `versionCheck` —— `sampleThreshold`、`declaredGroups`、`observedGroups`、`missingGroups`、`unexpectedGroups`。

版本维度指标**不新增存储**：复用既有的「路由 × 上游实例」维度，给每个转发过的实例补上 `group`，
再把这些行按版本汇总。5 分钟滑动窗口与 `route_upstream_sum_equals_instance_sum` 不变式仍然成立。

时间口径是**刻意混用**的，务必分清：

- `windowRequests`、`status5xx`、`connectFail`、`timeout`、`avgMillis`、`p95Millis`、`sampleSize`、
  `sufficient`、`errorRate` 都是**窗口内**口径（`range=60/300`）；
- `noUpstreamRejects` / `circuitOpenRejects` 取自路由维度的**累计**拒绝计数（与
  `resources.rejects.noUpstream` / `circuitOpen` 同一套累计口径），**不带时间衰减**——这样就不必为版本维度
  再建一套按时间环的存储；
- 因此 `capacityProblem` 表示「该版本自启动以来出现过无实例 / 全熔断的 503」，是一个**保守信号**，
  不等于「当前窗口内一定有问题」。判断当前窗口是否异常要以 `windowRequests` / `status5xx` / `sampleSize` 为主；
- 窗口样本数低于 `sampleThreshold`（`MIN_VERSION_SAMPLE = 5`）的版本不足以据此判定（`sufficient=false`）；
- `p95Millis` 是版本内各实例 p95 的**最大值**（保守上界），没有做跨实例样本合并，不要当成版本真实的 p95。

## 6. 边界（本阶段不做）

- **异常自动停推**不做。它需要一个「驱动者」；判断逻辑应放在 Java 侧可测服务里，由下一阶段 Agent 调用。
  本阶段提供的是原语——把某版本权重置 `0` 一键停推，加上可信的按版本指标与样本量阈值。
- Agent 侧写操作仍关闭（`executable` 恒为 `false`，无审批、审计、任务持久化）。写闭环是下一阶段。
- 不做多节点逐节点生效确认；Admin 当前只面向单个 `gatewayUrl`。
- 除上述原语外，网关侧不做自动护栏（按 5xx 率自动降权重）。
- Nameserver 不提供独立的「暂停接流 / 恢复接流」状态；版本分流靠 `group` 已可实现。
- 除 `revision` 之外不做审计持久化。
- 前端只改了路由编辑弹窗（加预览与冲突提示），不做新的发布控制台。
