# Rover-Admin API

Admin API is same-origin with the console at `http://127.0.0.1:9090` and all
paths use the `/api` prefix. Admin itself does not add authentication to
`/api/*`; protect port 9090 with a private network, ACL, VPN, or reverse proxy.
`rover.admin.admin-token` is used when Admin calls Gateway and Nameserver and is
sent as `X-Rover-Admin-Token`.

| Method | Path | 说明 |
| --- | --- | --- |
| GET | `/api/overview` | 组件状态、发现模式、自洽检查 |
| GET | `/api/live?range=60\|300` | 轻量实时快照 |
| GET | `/api/routes` | 路由列表 |
| POST | `/api/routes` | 新增或更新路由，body 为路由对象 |
| DELETE | `/api/routes?businessPrefix=/api/demo` | 删除路由 |
| GET | `/api/instances` | Nameserver 实例列表 |
| GET | `/api/nameserver/metrics` | Nameserver 指标 |
| GET | `/api/events` | 最近事件 |
| GET | `/api/traces?traceId=&path=&slow=` | Gateway 追踪记录 |
| GET | `/api/configs` | Gateway/Nameserver 配置元数据 |
| POST | `/api/configs` | 更新配置 |
| POST | `/api/agent/diagnoses` | 创建只读诊断任务；body 为 `{"path":"/api/demo/tt","question":"为什么失败？"}` |
| GET | `/api/agent/diagnoses/{taskId}` | 查询任务状态、执行步骤、结论及证据 |

配置更新请求示例：

```json
{"component":"gateway","key":"gateway.trace.sampleRate","value":"1"}
```

成功响应包含 `component`、`key`、`message`；错误响应按 HTTP 状态码和 `message` 处理。`gateway.loadbalance.strategy` 属于启动/插件装配配置，不通过 Admin API 修改。避免把 token 写入日志。

诊断任务状态为 `PENDING`、`RUNNING`、`COMPLETED` 或 `FAILED`。结果包含
`summary`、`confidence`、带来源和采集时间的 `evidence`、`limitations`、
假设验证 `hypotheses`（每条含 `id`、`statement`、`status`、`detail`、`sources`，
`status` 为 `CONFIRMED`、`REJECTED` 或 `UNKNOWN`），以及
配置模型后可选的 `aiAnalysis`。任务存于 Admin 内存，重启后不可查询。
