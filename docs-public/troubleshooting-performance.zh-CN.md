# 故障排查与性能边界

| 现象 | 优先检查 |
| --- | --- |
| Gateway 找不到实例 | Nameserver TCP 地址、协议 token、服务名和心跳间隔 |
| Admin 显示组件离线 | Gateway/Nameserver 管理地址、管理 token、管理端口 ACL；可先 curl `/_manage/health` |
| 配置保存失败 | 字段类型、枚举值、热更新标记和下游日志；不要把采样率填到策略字段 |
| 路由返回 404 | `businessPrefix`、`stripPrefix`、发现模式及服务实例健康状态 |
| 频繁摘除实例 | 心跳超时、网络抖动、实例注册的 host/port 是否可达 |
| HTTP 注册返回 401 | `Authorization: Bearer` 与 Nameserver 协议 token 是否一致 |
| 管理接口返回 401 | `X-Rover-Admin-Token` 是否使用对应组件的管理 token |
| 日志刷屏 / 磁盘涨 | 确认访问日志为 DEBUG；或设 `filters.accessLog: false`；Nameserver 连断已是 DEBUG |

## 排障日志怎么开

默认 **INFO**：启动、热更新、鉴权失败、上游/协议错误。  
热路径访问完成、路由命中、Nameserver TCP 连/断为 **DEBUG**（默认不刷盘）。

临时打开（Logback 示例）：

```xml
<logger name="com.rover.gateway.core.filter" level="DEBUG"/>
<logger name="com.rover.nameserver.core.server.NameserverServerHandler" level="DEBUG"/>
```

## 性能边界（怎么测、怎么记）

Rover-Suite **不宣称固定 QPS 上限**。实际吞吐取决于路由数量、上游耗时、连接复用、JDK/CPU、指标窗口、追踪采样率和部署网络。

Admin 是观测面，不是压测工具；压测时应**不启动 Admin**，或关闭页面实时轮询。

下面记录的是 **2026-08-24** 在作者本机跑通的一轮**限额参考压测**：用 Docker Compose 把三件套 CPU/内存卡在小团队常见单机档位，在 **Mac Docker Desktop** 上测得。数字用于说明「资源 modest 时仍可用」，**不是** Linux 裸机 SLA，也**不是**宿主机满核跑分。

---

### 测试环境（务必读）

| 项 | 说明 |
| --- | --- |
| **宿主机** | Apple Silicon Mac（**M5 Pro**），macOS |
| **容器运行时** | **Docker Desktop for Mac**（非 Linux 裸机、非 K8s） |
| **Docker Desktop 内存** | **16 GB**（Settings → Resources） |
| **压测工具** | 宿主机安装 **`hey`**（`brew install hey`），在 Mac 上直接打 `127.0.0.1`，**不在容器内**跑 |
| **容器基础镜像** | `eclipse-temurin:17-jre` |
| **JVM 堆策略** | 各容器 `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0`（配合 `mem_limit` 吃满限额，避免默认 25% 过小） |
| **Compose 文件** | `docker-compose.yml` + `docker-compose.perf-small.yml` |
| **一键脚本** | `./deploy/scripts/bench-small-team.sh` |
| **测试日期** | 2026-08-24 |

**Docker Desktop 与生产的差异（对外要讲清楚）：**

- macOS 上 Docker 走虚拟化层，网络与 CPU 调度与 Linux 物理机/云主机不同，**同限额在 Linux 上通常更高或更稳**。
- 参照表数字是 **「限额 + Docker Desktop + 本机参考」**，只能写「摸底 / 参考」，不能写「保证达到 X QPS」。
- 宿主机是 M5 Pro，但 Compose **故意限 CPU/内存**，成绩反映的是 **small≈4C8G 叙事**，不是 M5 满配。

---

### 被测拓扑与资源限额（small 档）

**只起三件套，不启 Admin：**

| 组件 | 端口 | CPU 限额 | 内存限额 | 压测路径 |
| --- | --- | --- | --- | --- |
| Nameserver | 8888 / 8889 | 1.0 | 1 GB | 不参与 HTTP 压测 |
| Gateway | 8080 | 2.0 | 2 GB | `GET /api/hello` |
| demo 上游 | 8082（映射到宿主机） | 0.5 | 768 MB | `GET /api/hello`（直连对照） |

**合计约 CPU 3.5 / 内存 ~3.75 GB**，刻意留给 Docker Desktop 与 macOS 余量，对应「不太宽裕的 4C8G 小团队单机」叙事。

**经 Gateway 路径包含：** Nameserver 服务发现 + 路由匹配 + HTTP 代理转发 + 默认 metrics（未开追踪采样加压）。

**直连路径：** 宿主机 `127.0.0.1:8082` 打 demo 容器，**绕过 Gateway 与 Nameserver**，作为 baseline。

更穷一档（**tiny≈2C4G**）见 `docker-compose.perf-tiny.yml`，本次未跑，可 `BENCH_PROFILE=tiny ./deploy/scripts/bench-small-team.sh` 补测。

---

### 压测方法

1. 构建并启动：`BENCH_KEEP=1 ./deploy/scripts/bench-small-team.sh`（或脚本内等价步骤）。
2. 探活：`/_manage/health` + 业务路径 `/api/hello` 返回 200 后再压。
3. **主对比**（正式对外只用这组参数）：
   - 工具：`hey -z 30s -c 50`
   - Gateway：`http://127.0.0.1:8080/api/hello`
   - 直连：`http://127.0.0.1:8082/api/hello`
4. **重复性：** 主对比各跑 **3 轮**，取均值与波动范围。
5. **并发对照：** 直连 `hey -z 30s -c 100`，验证 503 是否来自 Gateway 限额而非上游。
6. 记录 QPS、P50/P95/P99、HTTP 状态码分布；**不要用** Gateway `c=100` 的 QPS 当正式成绩。

---

### 实测结果（small 限额，无 Admin）

#### 主对比：hey 30s，c=50，3 轮明细

| 轮次 | 路径 | QPS | P50 | P95 | P99 | 响应数 | 错误 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| #1 | 经 Gateway `:8080` | 2560 | 12.1 ms | 65.0 ms | 79.8 ms | 76812 | 0 |
| #2 | 经 Gateway | 2353 | 13.2 ms | 67.2 ms | 85.8 ms | 70606 | 0 |
| #3 | 经 Gateway | 2575 | 12.1 ms | 63.8 ms | 78.8 ms | 77279 | 0 |
| #1 | 直连 demo `:8082` | 3907 | 2.2 ms | 85.4 ms | 90.0 ms | 117432 | 0 |
| #2 | 直连 demo | 4036 | 2.1 ms | 84.8 ms | 89.7 ms | 121133 | 0 |
| #3 | 直连 demo | 4216 | 2.0 ms | 84.0 ms | 90.4 ms | 126565 | 0 |

#### 主对比汇总（3 轮均值）

| 路径 | QPS 均值 | QPS 范围 | P50 均值 | P95 均值 | P99 均值 |
| --- | ---: | --- | ---: | ---: | ---: |
| **经 Gateway** | **2496** | 2353～2575 | **12.5 ms** | **65 ms** | **82 ms** |
| **直连 demo** | **4053** | 3907～4216 | **2.1 ms** | **85 ms** | **90 ms** |

Gateway 三轮 QPS 波动约 **±9%**，在 Docker Desktop 本机摸底属正常范围；**六段压测 HTTP 状态码均为 200**。

#### 并发对照：hey 30s

| 路径 | 并发 c | QPS | P50 | P95 | P99 | 说明 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 直连 demo `:8082` | **100** | **4245** | 3.6 ms | 93 ms | 101 ms | **127380 次全 200** |
| 经 Gateway `:8080` | 100 | — | — | — | — | **大量 503**（Gateway 2 CPU 限额被打满，勿作正式对比） |

直连在 **c=100 仍全 200**，QPS 与 c=50 同量级（~4.2k），说明 **503 来自 Gateway 容器限额**，不是 demo 上游扛不住。

---

### 结论（怎么读、怎么对外说）

**网关引入的成本（c=50，取 3 轮均值）：**

| 指标 | 数值 | 含义 |
| --- | --- | --- |
| 吞吐保留比 | **2496 ÷ 4053 ≈ 62%** | 经 Gateway 仍约 **2.5k QPS** |
| P50 增量 | **12.5 − 2.1 ≈ 10 ms** | 中位延迟主要花在发现 + 转发链路上 |
| P95 / P99 | GW **65 / 82 ms** vs 直连 **85 / 90 ms** | 尾延迟同量级，受容器抖动与上游影响 |

**推荐对外表述（可直接引用，记得带前提）：**

> 在 **Mac Docker Desktop** 上，用 Compose 将 Gateway / Nameserver / demo **限额到约 4C8G 小团队单机档位**（**未启动 Admin**），对同一 `GET /api/hello` 压测 30 秒、并发 50、重复 3 轮：经 Gateway 约 **2.5k QPS**、P50 **~12 ms**；直连 demo 约 **4k QPS**、P50 **~2 ms**。网关吞吐约为直连的 **62%**，中位延迟多约 **10 ms**。并发加到 100 时直连仍全 200，经 Gateway 出现大量 503，属 **Gateway CPU 限额** 现象。以上为 **Docker Desktop 参考摸底**，不等同 Linux 生产环境 SLA。

**不要这样写：**

- ❌ 「M5 Pro 跑满 X 万 QPS」—— 实际测的是 **限额容器**，不是满配宿主机。
- ❌ 「保证小团队 2500 QPS」—— 无 SLA，路由/上游一变数字就变。
- ❌ 把 Gateway `c=100` 的 QPS 与 c=50 混比—— 503 占主导时 QPS 无意义。

---

### 复现命令

```bash
# 依赖
brew install hey

# 一键（small 档，含 Gateway c50/c100 + 直连 c50）
cd /path/to/rover-suite
BENCH_KEEP=1 ./deploy/scripts/bench-small-team.sh

# 重复性：主对比各 3 轮
for i in 1 2 3; do
  hey -z 30s -c 50 http://127.0.0.1:8080/api/hello
  hey -z 30s -c 50 http://127.0.0.1:8082/api/hello
done

# 直连高并发对照
hey -z 30s -c 100 http://127.0.0.1:8082/api/hello

# 收摊
cd deploy/docker
docker compose -f docker-compose.yml -f docker-compose.perf-small.yml down
```

Compose 冒烟（功能验证，不是压测）：`./deploy/scripts/smoke-compose.sh`。

限额文件与脚本说明见 [`deploy/docker/README.md`](../../deploy/docker/README.md)。
