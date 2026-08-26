# 性能测试指南

这份文档说明如何为 Rover-Suite 做可复现的性能测试。它适合用于本地评估、部署前容量验证，以及定位 Gateway 链路里的主要瓶颈。

如果你只是想快速体验项目，不需要先读这篇。建议先完成[快速上手](./quick-start.zh-CN.md)，确认 Nameserver、Gateway 和 Demo 服务都能正常工作后，再进行压测。

## 1. 测试目标

压测不只看 QPS。更重要的是弄清楚慢在哪里。

建议至少回答这些问题：

- 直接访问上游服务时，机器本身能跑到什么水平。
- Gateway 最小代理链路比直连多了多少耗时。
- Nameserver 动态发现比静态上游多了多少开销。
- metrics、trace、access log、rate limit、自定义 Filter 分别增加多少开销。
- 路由数量、请求体大小、响应体大小是否会放大 P95/P99。
- Gateway 的稳定吞吐拐点在哪里。
- 如果和 Nginx 对比，差距来自专用代理实现，还是 Rover-Suite 自身链路。

## 2. 推荐测试顺序

建议按下面顺序测试。每一步只改变一个变量，结果才容易解释。

```text
Direct Demo
  ↓
Gateway 静态路由最小链路
  ↓
Gateway + Nameserver 动态发现
  ↓
Gateway + metrics / trace / access log / rate limit
  ↓
Gateway + 用户 Filter / 用户 LoadBalancer
  ↓
Payload、路由数量、上游延迟、并发饱和点
  ↓
Nginx 基线对比
```

如果 Gateway 静态最小链路已经明显变慢，建议先分析代理实现、线程切换、请求/响应聚合和对象创建。不要急着把所有功能都打开一起测。

## 3. 测试环境记录

性能报告里建议先写清楚测试环境。不同机器、JDK、Docker 环境的结果不能直接混用。

| 字段 | 建议记录 |
| --- | --- |
| Git commit | 对应源码版本 |
| 操作系统 | Windows / Linux / macOS |
| CPU | 型号、核心数 |
| 内存 | 总内存 |
| JDK | 版本和发行版 |
| 启动方式 | Docker / 本地 Java / IDE |
| Docker 资源限制 | CPU、内存、网络模式 |
| 压测工具 | `hey` / `wrk` 版本 |
| Gateway JVM 参数 | 堆大小、GC、线程相关参数 |
| Admin 状态 | 是否启动，`gateway.adminEnabled` 是否开启 |

如果使用 Docker Desktop，需要在报告里说明。Docker Desktop 的虚拟化和网络转发会影响测试结果，不能直接代表 Linux 生产环境。

## 4. 基础测试命令

可以使用 `hey` 做第一轮测试。

```bash
hey -z 30s -c 50 http://127.0.0.1:80/api/hello
```

常用参数：

| 参数 | 说明 |
| --- | --- |
| `-z 30s` | 持续测试 30 秒 |
| `-c 50` | 并发 50 |
| URL | 被测接口地址 |

建议每个测试点至少跑 5 次，并保存原始输出。

### 4.1 仓库一键脚本（Phase A）

仓库提供可复现脚本（需 `hey`、Docker）：

| 脚本 | 用途 |
| --- | --- |
| `deploy/scripts/bench-phase-a.sh` | Direct / Gateway 静态 / Gateway+NS，`GET /api/hello` |
| `deploy/scripts/bench-phase-a-payload.sh` | 历史第一波：定长大包整包 vs 流式 A/B（会回退源码，脏工作区别跑） |
| `deploy/scripts/bench-phase-a-payload-remasure.sh` | 当前代码复测：大包 GET + POST `/api/ingest`，同场 Netty / JDK 出站 |
| `deploy/scripts/bench-phase-a-static-ab.sh` | 同场 A/B：无静态快照 vs 有快照；Direct 中位差 >10% 作废重跑 |
| `deploy/scripts/bench-phase-a-outbound-ab.sh` | 同场 A/B：只改 `proxy.outbound`（jdk / netty） |
| `deploy/scripts/bench-phase-a-io-ab.sh` | 同场 A/B：只改 `server.ioTransport`（nio / auto）。Docker Linux 容器里 `auto` 一般为 epoll |

当前代码大包 / POST 复测：

```bash
chmod +x deploy/scripts/bench-phase-a-payload-remasure.sh
SIZES="262144 1048576 4194304" CONCURRENCY=20 DURATION=20s ROUNDS=3 \
  ./deploy/scripts/bench-phase-a-payload-remasure.sh
```

结果整理方式见[性能报告](./performance-report.zh-CN.md) §7。


### 5.1 Direct Demo

先直接访问上游服务。

```bash
hey -z 30s -c 50 http://127.0.0.1:8082/api/hello
```

这组数据是上游基线。如果 Demo 直连已经很慢，后续 Gateway 数据就不能直接归因给 Gateway。

### 5.2 Gateway 静态路由最小链路

使用静态上游地址，不经过 Nameserver。关闭 metrics、trace、rate limit 和请求级日志。  
Phase A 配置（`rover-gateway-static.yml` / `rover-gateway-ns-min.yml`）已写 `metrics.enabled=false`、`trace.enabled=false`、`dispatchOnEventLoop=false`；`enabled=false` 时热路径不再 `markPhase` / 记 inflight / 造 trace UUID。

这一步用于观察 Gateway 的基础代理开销。

建议记录：

- Gateway QPS
- P50 / P95 / P99
- 2xx / 4xx / 5xx
- Gateway CPU、内存、GC
- Demo CPU、内存、GC

判断方式：

```text
Gateway 基础代理开销 = Gateway 静态链路耗时 - Direct Demo 耗时
```

### 5.3 Gateway + Nameserver

切换到 Nameserver 动态发现，其他功能保持关闭。

这一步用于观察服务发现缓存、实例选择和负载均衡的额外开销。

判断方式：

```text
Nameserver 模式额外开销 = Gateway Nameserver 模式耗时 - Gateway 静态链路耗时
```

正常情况下，Gateway 不应该在每个请求里远程查询 Nameserver。请求期应该主要读取本地缓存。

### 5.4 功能开销拆分

每次只打开一个功能。

| 测试项 | 目的 |
| --- | --- |
| metrics off/on | 观察指标采集成本 |
| trace off/on | 观察请求追踪成本 |
| trace `sampleRate=1` | 观察全量追踪成本 |
| access log off/on | 观察请求级日志成本 |
| rate limit off/on | 观察限流判断成本 |
| 空 Filter 插件 | 观察插件框架成本 |
| 轻量 Filter 插件 | 观察普通业务插件成本 |
| 自定义 LoadBalancer | 观察实例选择算法成本 |

压测时不建议使用 DEBUG 日志。大量控制台输出会让测试结果变成日志 I/O 性能，而不是 Gateway 性能。

### 5.5 Payload、路由数量和上游延迟

这几组用于进一步定位瓶颈。

| 变量 | 建议取值 | 主要观察 |
| --- | --- | --- |
| Payload 大小 | 0B、10KB、100KB、1MB | 请求/响应聚合和内存复制 |
| 路由数量 | 1、10、100、1000 | 路由匹配是否线性变慢 |
| 上游延迟 | 10ms、50ms、100ms、500ms、2s | 慢上游下连接和排队表现 |
| 并发数 | 10、25、50、100、200、400 | 吞吐拐点和 P99 拐点 |

如果 Payload 变大后 P99 明显升高，重点看 body 聚合、byte array 拷贝和 GC。

如果路由数量增加后耗时线性升高，重点看路由匹配结构。

如果 CPU 不高但 P99 很高，重点看连接池、线程池排队和上游等待。

## 6. 需要记录的结果字段

每组测试建议记录同一套字段。

| 字段 | 说明 |
| --- | --- |
| case id | 测试编号 |
| URL | 被测地址 |
| HTTP 方法 | GET / POST |
| 并发数 | 例如 50 |
| 持续时间 | 例如 30s |
| 重复轮次 | 第几轮 |
| QPS | Requests/sec |
| 平均耗时 | Average latency |
| P50 | 普通请求体验 |
| P95 | 常用尾延迟指标 |
| P99 | 高位尾延迟指标 |
| Max | 最大耗时 |
| 2xx / 4xx / 5xx | 状态码分布 |
| timeout | 超时数量 |
| error rate | 错误率 |
| Gateway CPU / 内存 / GC | Gateway 资源表现 |
| Nameserver CPU / 内存 | 动态发现模式下记录 |
| Demo CPU / 内存 / GC | 判断上游是否被打满 |
| 配置快照 | 对应 YAML、Admin 状态、插件状态 |
| 原始输出文件 | `hey` 或 `wrk` 原始结果 |

判断稳定吞吐时，建议先看错误率，再看 P99，最后看 QPS。

## 7. 插件测试记录

Filter 和 LoadBalancer 都会进入请求链路，所以插件测试需要单独记录。

| 字段 | 说明 |
| --- | --- |
| 插件类型 | Filter / LoadBalancer |
| 插件名称 | `getName()` 或策略名 |
| 插件类名 | 完整类名 |
| 插件 JAR 路径 | 例如 `plugins/demo-plugin.jar` |
| 加载方式 | SPI / class path 显式配置 |
| 是否需要 YAML 声明 | SPI 通常不需要；class path 需要 |
| 是否有远程调用 | 插件里不建议做阻塞远程调用 |
| 是否打印请求级日志 | 会影响压测结果 |
| 是否验证成功加载 | 看 Gateway 启动日志 |
| 是否验证真实生效 | 看请求结果或业务日志 |

SPI 方式适合插件 JAR 内声明后自动加载。class path 显式配置适合按环境控制是否加载。

## 8. 与 Nginx 对比

Nginx 可以作为专用反向代理基线。它和 Rover Gateway 的定位不同，但对比有助于判断基础代理链路是否偏重。

建议至少对比四组：

| 组别 | 目的 |
| --- | --- |
| Direct Demo | 上游服务基线 |
| Nginx -> Demo | 专用反向代理基线 |
| Rover Gateway 静态链路 | Rover 基础代理开销 |
| Rover Gateway + Nameserver | Rover 完整发现链路 |

如果 Nginx 明显更快，这是合理现象。Nginx 是成熟的专用代理，核心路径很短。Rover Gateway 还包含 Java Filter、服务发现、路由、负载均衡、指标和插件扩展。

更有价值的结论是：

```text
Rover 静态链路比 Direct Demo 多多少
Rover Nameserver 链路比静态链路多多少
每个功能打开后分别多多少
```

## 9. 常见瓶颈判断

| 现象 | 可能原因 | 建议方向 |
| --- | --- | --- |
| Direct Demo 也慢 | 上游服务或机器资源不足 | 先优化上游或换更稳定环境 |
| Gateway 静态链路明显慢 | 代理实现、线程切换、body 聚合、对象创建 | 重点分析 Gateway 热路径 |
| Nameserver 模式明显慢 | 发现缓存、实例选择或负载均衡实现偏重 | 检查是否请求期远程查询或复制实例列表 |
| metrics 打开后 P99 上升 | 指标热路径成本偏高 | 降低标签维度，减少请求期对象创建 |
| trace 全量开启后变慢 | 追踪记录成本偏高 | 使用采样或只记录慢请求 |
| access log 打开后变慢 | 请求级日志 I/O 成本高 | 使用异步日志或降低日志量 |
| Payload 增大后 P99 飙升 | 聚合、复制或 GC 压力 | 控制大小，评估流式转发 |
| 路由数量增加后线性变慢 | 路由匹配结构需要优化 | 预编译路由或使用更高效索引 |
| CPU 不高但延迟高 | 连接池、线程池排队或上游等待 | 抓线程栈，检查 in-flight 请求 |
| CPU 高但 QPS 不高 | CPU 热点或对象分配多 | 使用 profiler 或 JFR 定位 |

## 10. 报告建议结构

正式报告建议包含：

```text
1. 测试环境
2. 测试拓扑
3. 测试方法
4. Direct Demo 基线
5. Gateway 静态链路
6. Gateway + Nameserver
7. 功能开销拆分
8. 插件开销
9. Payload / 路由数量 / 上游延迟影响
10. Nginx 基线对比
11. 瓶颈分析
12. 后续优化计划
13. 原始数据位置
```

公开报告里建议保留原始输出和测试配置。这样用户可以复现，也能判断这组数据是否适合自己的场景。
