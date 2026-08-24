# 性能报告与压测方法

这份文档说明 Rover-Suite 当前的一组本地参考结果。若你准备在自己的机器上做容量评估，建议先阅读[性能测试指南](./benchmark-guide.zh-CN.md)，再按本文格式整理结果。

## 1. 先看结论

Rover-Suite 不提供通用 QPS 承诺。真实吞吐会受到 CPU/JDK、容器限额、路由数量、上游耗时、连接复用、网络位置、指标窗口和追踪采样率影响。

当前仓库记录的是一组 **本地 Docker Desktop 参考样本**。它能说明在受限资源下 Gateway 链路可以跑通并观察开销，但不能直接代表 Linux 生产环境 SLA。

如果要给自己的环境做容量判断，建议直接复用本文命令，在目标机器上跑出自己的数据。

## 2. 为什么这组数据看起来不够直觉

当前样本里有两个容易让人疑惑的点：

1. **直连 demo 的 P95/P99 比 Gateway 更高。**  
   这通常说明尾延迟受 Docker Desktop 调度、宿主机状态或 demo 容器抖动影响。它不表示 Gateway 能优化上游尾延迟。更稳的做法是增加轮次，记录原始 `hey` 输出，并补充 CPU、GC 和容器资源曲线。

2. **Gateway 并发 100 只记录“大量 503”。**  
   这组数据已经进入 Gateway CPU 限额瓶颈。它适合说明“资源不足时会失败”，不适合和并发 50 的稳定吞吐放在一起计算性能结论。

所以这组数据定位为 **参考样本**，不是正式基准。后续如果要增强说服力，建议补齐原始输出、机器型号、Docker 配置、CPU 曲线、GC 日志和多轮统计。

## 3. 测试环境

| 项 | 说明 |
| --- | --- |
| 测试日期 | 2026-08-24 |
| 宿主机 | Apple Silicon Mac，macOS |
| 容器运行时 | Docker Desktop for Mac |
| Docker Desktop 内存 | 16 GB |
| 压测工具 | 宿主机 `hey`，直接请求 `127.0.0.1` |
| 容器基础镜像 | `eclipse-temurin:17-jre` |
| JVM 堆策略 | `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0` |
| Compose 文件 | `docker-compose.yml` + `docker-compose.perf-small.yml` |
| 一键脚本 | `./deploy/scripts/bench-small-team.sh` |

Docker Desktop 与 Linux 生产环境存在差异。macOS 虚拟化层会影响网络和 CPU 调度，所以同一份限额在 Linux 物理机或云主机上可能得到不同结果。

## 4. 被测拓扑

本次只启动 Nameserver、Gateway 和 demo 上游，不启动 Admin。

| 组件 | 端口 | CPU 限额 | 内存限额 | 压测角色 |
| --- | --- | ---: | ---: | --- |
| Nameserver | 8888 / 8889 | 1.0 | 1 GB | 提供服务发现 |
| Gateway | 8080 | 2.0 | 2 GB | 代理 `GET /api/hello` |
| demo 上游 | 8082 | 0.5 | 768 MB | 直连 baseline |

经 Gateway 的路径包含：路由匹配、Nameserver 发现缓存、负载均衡、HTTP 代理转发和默认 metrics。

直连路径为：宿主机直接访问 `127.0.0.1:8082/api/hello`，绕过 Gateway 与 Nameserver。

## 5. 压测方法

主对比使用 `hey -z 30s -c 50`，Gateway 和直连各跑 3 轮。

```bash
brew install hey

cd /path/to/rover-suite
BENCH_KEEP=1 ./deploy/scripts/bench-small-team.sh

for i in 1 2 3; do
  hey -z 30s -c 50 http://127.0.0.1:8080/api/hello
  hey -z 30s -c 50 http://127.0.0.1:8082/api/hello
done
```

并发 100 的直连对照用于确认上游是否还能处理请求：

```bash
hey -z 30s -c 100 http://127.0.0.1:8082/api/hello
```

如果要关闭环境：

```bash
cd deploy/docker
docker compose -f docker-compose.yml -f docker-compose.perf-small.yml down
```

## 6. 参考结果

### 6.1 主对比：hey 30s，c=50，3 轮明细

| 轮次 | 路径 | QPS | P50 | P95 | P99 | 响应数 | 错误 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| #1 | 经 Gateway `:8080` | 2560 | 12.1 ms | 65.0 ms | 79.8 ms | 76812 | 0 |
| #2 | 经 Gateway | 2353 | 13.2 ms | 67.2 ms | 85.8 ms | 70606 | 0 |
| #3 | 经 Gateway | 2575 | 12.1 ms | 63.8 ms | 78.8 ms | 77279 | 0 |
| #1 | 直连 demo `:8082` | 3907 | 2.2 ms | 85.4 ms | 90.0 ms | 117432 | 0 |
| #2 | 直连 demo | 4036 | 2.1 ms | 84.8 ms | 89.7 ms | 121133 | 0 |
| #3 | 直连 demo | 4216 | 2.0 ms | 84.0 ms | 90.4 ms | 126565 | 0 |

### 6.2 主对比汇总

| 路径 | QPS 均值 | QPS 范围 | P50 均值 | P95 均值 | P99 均值 |
| --- | ---: | --- | ---: | ---: | ---: |
| 经 Gateway | 2496 | 2353～2575 | 12.5 ms | 65 ms | 82 ms |
| 直连 demo | 4053 | 3907～4216 | 2.1 ms | 85 ms | 90 ms |

在这组本地样本中，Gateway 吞吐约为直连的 62%，P50 多约 10 ms。这个差值包含路由、发现缓存、负载均衡、代理转发和指标记录成本。

### 6.3 并发对照

| 路径 | 并发 | 结果 | 说明 |
| --- | ---: | --- | --- |
| 直连 demo `:8082` | 100 | 约 4245 QPS，全部 200 | 上游在该并发下仍可处理请求 |
| 经 Gateway `:8080` | 100 | 大量 503 | Gateway 2 CPU 限额下已进入瓶颈 |

Gateway 并发 100 的结果不用于计算稳定吞吐。它只说明在当前限额下，Gateway 资源不足时会返回错误。

## 7. 如何让压测结果更可信

如果准备把压测结果用于发布说明、选型或容量规划，建议补齐这些材料：

1. 保存每一轮 `hey` 原始输出。
2. 至少跑 5～10 轮，并记录均值、最小值、最大值和标准差。
3. 同时记录 Gateway、Nameserver 和上游的 CPU、内存、GC、线程数。
4. 把 Admin 关闭，或说明 Admin 页面是否开启实时轮询。
5. 固定路由数量、上游响应体大小、连接复用策略和日志级别。
6. 在目标 Linux 环境重跑，不直接使用 Docker Desktop 数据做生产判断。
7. 对异常样本保留说明，不把错误状态码的结果混入成功吞吐。

## 8. 本文数据怎么使用

这组数据可以作为本地开发和小资源档位的参考起点。它不能作为项目性能承诺，也不建议用于和其他 Gateway 产品做正式横向对比。

更可靠的做法是：把本文命令复制到目标环境，保留原始输出，再结合业务路由、上游耗时和资源限制生成自己的报告。
