# Rover-Admin API

Admin API 默认与控制台同源，地址为 `http://127.0.0.1:9090`，所有接口前缀为 `/api`。Admin 本身是静态控制台加聚合层，默认不保存业务数据；它会调用 Gateway 和 Nameserver 的管理接口。

`rover.admin.admin-token` 用于 Admin 调用下游组件时发送 `X-Rover-Admin-Token`。它不会自动为 Admin 的 `/api/*` 接口增加登录认证，因此生产环境仍需通过绑定地址、防火墙、反向代理或 VPN 限制 9090 的访问来源。

## 接口目录

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/overview` | Gateway/Nameserver 状态、发现模式和指标自洽检查 |
| GET | `/api/live?range=60\|300` | 轻量实时快照，供仪表盘轮询 |
| GET | `/api/routes` | Gateway 路由列表 |
| POST | `/api/routes` | 新增或更新路由，请求体为路由对象 |
| DELETE | `/api/routes?businessPrefix=/api/demo` | 按业务前缀删除路由 |
| GET | `/api/instances` | Nameserver 注册实例列表 |
| GET | `/api/nameserver/metrics` | Nameserver 指标快照 |
| GET | `/api/events` | Nameserver 最近事件 |
| GET | `/api/traces?traceId=&path=&slow=` | Gateway 请求链路记录及筛选 |
| GET | `/api/configs` | Gateway/Nameserver 配置元数据 |
| POST | `/api/configs` | 更新一个配置项 |

## 请求示例

读取仪表盘的 1 分钟窗口：

```bash
curl "http://127.0.0.1:9090/api/live?range=60"
```

新增或更新路由：

```bash
curl -X POST "http://127.0.0.1:9090/api/routes" \
  -H "Content-Type: application/json" \
  -d '{"id":"demo-api","businessPrefix":"/api","serviceName":"demo-service"}'
```

更新配置：

```bash
curl -X POST "http://127.0.0.1:9090/api/configs" \
  -H "Content-Type: application/json" \
  -d '{"component":"gateway","key":"gateway.loadbalance.strategy","value":"round_robin"}'
```

路由请求体字段应与 Gateway 的路由模型一致；配置更新的 `component` 只能是 `gateway` 或 `nameserver`，`key` 和 `value` 会由下游组件再次校验。

## 响应与错误

- 成功响应为 JSON。配置更新至少返回 `component`、`key` 和 `message`，部分配置会附带 `payload`。
- 下游不可达、鉴权失败或参数校验失败时，Admin 返回对应的 HTTP 错误状态；同时页面会显示失败提示。
- 指标聚合接口在旧版本组件缺少指标端点时，会返回包含 `error` 的结构化 JSON，而不是让整个仪表盘崩溃。
- 不要把 `X-Rover-Admin-Token`、协议 token、Cookie 或完整请求体写入日志。

## 轻量调用建议

- `/api/live` 只在仪表盘可见时按约 1 秒轮询；后台标签页和其他页面不持续拉取全量数据。
- `/api/overview` 适合低频探活，不要把它当作高频监控采集接口。
- `/api/traces` 的数据量受 Gateway 采样率和环形缓冲限制；排查慢请求时优先使用 `slow=1`，不要长期打开全量采样。
- 写接口成功后再刷新列表，避免重复提交同一个配置或路由变更。

Admin API 是同版本控制面接口。客户端只应依赖本文列出的路径、参数和字段，不要依赖未文档化的聚合内部字段；升级 Gateway、Nameserver 和 Admin 时应保持同一版本。

