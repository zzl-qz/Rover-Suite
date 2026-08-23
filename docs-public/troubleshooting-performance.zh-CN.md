# 故障排查与性能边界

| 现象 | 优先检查 |
| --- | --- |
| Gateway 找不到实例 | Nameserver TCP 地址、协议 token、服务名和心跳间隔 |
| Admin 显示组件离线 | Gateway/Nameserver 管理地址、管理 token、管理端口 ACL |
| 配置保存失败 | 字段类型、枚举值、热更新标记和下游日志；不要把采样率填到策略字段 |
| 路由返回 404 | `businessPrefix`、`stripPrefix`、发现模式及服务实例健康状态 |
| 频繁摘除实例 | 心跳超时、网络抖动、实例注册的 host/port 是否可达 |
| HTTP 注册返回 401 | `Authorization: Bearer` 与 Nameserver 协议 token 是否一致 |
| 管理接口返回 401 | `X-Rover-Admin-Token` 是否使用对应组件的管理 token |

项目不宣称固定 QPS 上限。吞吐取决于路由数量、上游耗时、连接复用、JDK/CPU、指标窗口、追踪采样率和部署网络。
Admin 是观测面，不是压测工具；压测时应关闭页面实时轮询或只保留低频 overview。

每次发布建议记录 Gateway 直接转发吞吐和 P50/P95/P99、不同实例规模下的注册/心跳/推送开销、metrics/trace
开关下的 CPU/堆/GC，以及 Admin 管理请求速率，同时记录 CPU 核数、内存、JDK、系统和压测工具版本。
推荐使用 `wrk` 或 `hey` 产生业务流量，并同时采集 JVM、系统 CPU、GC、网络和 `/api/live` 响应。
