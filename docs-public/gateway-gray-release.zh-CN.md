# Gateway 版本化灰度发布

[English](./gateway-gray-release.md) · [文档索引](./README.md)

本文讲版本化路由（`targets`）、安全变更接口，以及支撑灰度的版本维度指标。路由写法与校验规则另见
[配置项参考：路由字段](./configuration-reference.zh-CN.md#路由字段)，管理端点另见
[使用指南：管理接口与 Admin](./user-guide.zh-CN.md#7-管理接口与-admin)。

## 1. 路由模型

一条路由的上游二选一：

- **静态** —— `targetUrl` 或 `targetUrls`；
- **版本化** —— `targets: [{serviceName, group, weight}]`。

两者在同一条路由上**互斥**，旧的扁平 `serviceName` / `group` 字段已删除。

`group` 是注册中心的**通用业务分组**（可用于隔离环境、租户、机房……），并不是为灰度发明的版本字段。
灰度只是**复用了这个维度**：把同一服务的多个分组当作多个版本（`v1`、`v2`、…），所以没有单独的版本字段
——注册中心本来就按 group 过滤，查询响应里也已经带 `revision` / `epoch`。这也意味着灰度分组与业务分组
共享同一个命名空间。

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

版本化路由分两级选择：先选版本，再在该版本内选实例。选版本用的是**固定刻度上的连续加权区间**：

```text
slot  = floorMod(fnv1a64(stickyKey + "|" + routeKey), SCALE)  // SCALE 是固定刻度，与权重之和无关
upper = SCALE × 累计权重 / 总权重                              // 第 k 个 target 的右边界
```

即先把哈希归一到 `[0, SCALE)`（`SCALE = 10_000_000`）上的一个定长槽位，再按 `targets` 的声明顺序把这条
定长区间按权重比例切给各 target，`slot` 落在哪个区间就选哪个 target。`weight: 0` 的 target 不占任何区间，
因此永远不接流。

刻度**必须**与权重之和无关：直接拿总权重取模的话，改一个 target 的权重就会改变 `total`，所有键的槽位
随之漂移。

### 为什么用连续区间，而不是一致性哈希环

在环上放 `weight` 个虚拟节点时，节点总数就等于总权重（典型只有 100 上下），比例误差受节点数限制，
**无法精确表达 5% 这类小比例**。固定刻度上的连续区间是精确比例，且是纯函数：`O(N)` 一次遍历，不用预计算环，
也不用跨层传递持有者对象。代价是失去一致性哈希「成员变更只重映射 1/N」的性质——本阶段不需要它，
因为放量是权重渐进调整，而固定刻度保证了每次调整只移动边界、不重排槽位。

### 放量是单调的

把权重从 `v1` 挪给紧邻其后的 `v2`（例如 `95/5 → 80/20`，总权重仍为 `100`）时，只会让 `v2` 的左边界左移，
右边界不动。于是原先落 `v2` 的键**一个都不会被打回 `v1`**——已经在灰度里的用户不会被换走，canary 的实验
连续性得以成立。

**只提高某一个版本的权重**（例如 `v1:95 / v2:20`，总权重从 `100` 变成 `115`）同样单调：某个 target 的权重
从 `w` 增到 `w+d` 时，左边界 `SCALE × 前序累计 / 总权重` 单调不增、右边界 `SCALE × 自身累计 / 总权重`
单调不减，因此它原有的区间只增不减，原持有的键一个不丢。注意此时的实际占比是**归一化**后的比例：
`v1:95 / v2:20` 下 `v2` 拿到 `20/115 ≈ 17.4%`，不是 `20%`。

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
- 因「没有可用上游」或「全部上游熔断」而回 `503` 时，`routeId` 与版本 `group` **已经挂在请求上下文上**，
  因此这类拒绝会归因到真实的路由与版本，而不是落进「未匹配路由」桶。挂晚了的话，现象是「某条路由没有
  可用上游」，指标上却表现为「路由表配错了」，真正的容量问题会被掩盖。

## 3. 安全变更接口

所有写操作都走同一条乐观锁协议。**所有写路径都必须带 `revision`**，没有绕过乐观锁的后门。

| 端点 | 方法 | 作用 |
| :--- | :--- | :--- |
| `/_manage/routes` | `GET` | 路由表 + `revision` + `appliedOperationId` |
| `/_manage/routes` | `PUT` | 整表替换：body `{revision, operationId, routes}`，`routes` 必须显式给出 |
| `/_manage/routes` | `POST` | 新增或替换单条：body `{revision, operationId, route}` |
| `/_manage/routes?id=\|businessPrefix=` | `DELETE` | 删除单条：query `revision`，可选 `operationId` |
| `/_manage/routes/preview` | `POST` | 只回逐条差异（`ADDED` / `REMOVED` / `MODIFIED`）与校验结果，不落盘、不生效 |
| `/_manage/routes/targets/weight` | `POST` | 放量 / 停推一个版本：`{revision, operationId, routeId, serviceName, group, weight}` |
| `/_manage/routes/rollback` | `POST` | 回滚：`{revision, operationId, toRevision}` |
| `/_manage/routes/operations/{operationId}` | `GET` | 请求超时后确认是否已执行 |

整表替换的 `routes` 字段是**严格解析**的：字段缺失、键名拼错、不是数组、数组里混入非对象元素，一律
`400` 拒绝；只有显式写 `[]` 才算「确实要清空路由表」。宽松解析会把「请求体写错了」变成「清空整张表并落盘」
——线上全站 404，而且重启也恢复不了。

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
  `APPLIED` / `CONFLICT` / `REJECTED` / `FAILED` / `UNKNOWN`，并回带 `currentRevision`。
- `FAILED` 表示**提交过但落盘失败**（变更没有生效）；它与 `REJECTED` 的区别是「写不进去」vs「校验没过」，
  两者都该被重试或修请求，而不是被当成「从未提交」。`UNKNOWN` 表示**没有记录**——可能从未提交，也可能已被
  有界的操作表（最近 `200` 条）淘汰；它**不等于**没有执行。
- Gateway 接受**发起方生成**的 `operationId`。Admin 的新增/更新、调权重和回滚会透传该 ID，
  这些 POST 写请求超时/网络失败后，前端通过 `GET /api/routes/operations/{operationId}` 回查，给出「已生效 /
  未生效（版本冲突）/ 未生效（校验失败）/ 未生效（落盘失败）/ 网关没有这条记录」的结论。
  当前 Admin 删除接口的 operationId 由服务端生成。
- 下游的 `409` 会**原样透传**到 Admin：状态码与「期望几、当前几」的文案都保留，不会被压成 `400`
  「参数写错了」——否则操作者只能反复重提同一个过期版本。

## 4. 版本可见性

两个只读端点让调用方不必猜就能对账版本：

- Nameserver `GET /_manage/instances/snapshot` —— 按 `service+group` 归并，带 `revision` / `epoch`，
  以及每个实例的 `instanceId`、`host`、`port`、`group`、`weight`、`healthy`、`ephemeral`、`lastHeartbeatMillis`。
- Gateway `GET /_manage/discovery/snapshot` —— 网关**自己观察到的**
  `(serviceName, group, revision, epoch, instanceCount, healthyCount)`。静态发现时如实返回
  `supported=false` 与空列表，不抛异常，于是「没有这项能力」与「读取失败」是可区分的两件事。

对比两者即可回答「我改了配置，网关是不是还没收到推送」。

### 服务发现的一致性保证

只读端点回答「现在是什么」，下面三条保证回答「变更会不会走到」：

- **迁组会通知旧组**：同一 `instanceId` 重新注册到另一个 `group` 时，旧组的订阅者同样收到推送。快照只带得动
  新组，不通知旧组的话，旧组会一直留着已经迁走的实例并把流量打过去。代价是每组最多多收一次「本组名单没变」
  的推送。
- **空名单是有效推送**：服务端每次真实变更都会 bump `revision`，所以「更新的 `revision` + 空列表」必须被客户端
  接受——那是某组最后一台实例下线、或整服务清空。只有**不比本地新**的空包才按乱序旧包丢弃（推空保护），
  避免旧包把更新的名单抹掉。
- **按 `service+group` 分键且不回落**：某个具体组没有自己的缓存时如实返回空、等该组订阅快照到位，绝不退回
  「整服务缓存」——退回会让 `v2` 的请求打到 `v1` 的实例上，灰度比例直接失真，而且现象上只表现为
  「`v2` 的上游是 `v1` 的地址」，极难一眼看出。

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

Admin 侧栏「指标诊断」或路由行的「诊断」入口可直接查看这些维度，切换 1/5 分钟并核对版本权重、实际请求数与实例指标。
全局 P99 在全局快照中展示，路由/实例视图当前展示 P95；累计拒绝、空样本和版本最大实例 P95 的口径见
[Admin 手册](./admin-guide.zh-CN.md#31-指标诊断与版本指标)。

## 6. 当前边界

- **异常自动停推**尚未实现。运维可结合版本指标和样本量，把某版本权重置 `0`；Agent 提议的变更仍需人工批准。
- Agent 已支持一类受控变更：提议版本权重调整，人工批准后预检、提交与回读，并保存变更记录；成功后可补偿回滚。
  不支持自动批准、摘实例、删除路由或任意配置修改，详见 [Ops Agent](./ops-agent.zh-CN.md)。
- 不做多节点逐节点生效确认；Admin 当前只面向单个 `gatewayUrl`。
- 除上述原语外，网关侧不做自动护栏（按 5xx 率自动降权重）。
- Nameserver 不提供独立的「暂停接流 / 恢复接流」状态；版本分流靠 `group` 已可实现。
- Gateway 操作记录保存在有界内存中；Admin 配置记录库后会持久化 Agent 变更记录。
- 发布操作使用现有路由编辑和 Agent 审批入口，没有独立的发布控制台。
