# 生产部署示例

Rover-Suite 当前定位是轻量单机或小规模内网部署。生产部署至少应把业务端口、注册协议端口、管理端口和 Admin
控制台分到明确的网络边界内。

小团队最短清单（含「是 / 不是」边界）：[小团队上线 10 条](./small-team-go-live.zh-CN.md)。

## 可抄改的配置样例

完整 YAML 模板（含限流、discovery、严格安全说明）：

- [`deploy/production/README.md`](../deploy/production/README.md)
- [`deploy/production/rover-nameserver.example.yml`](../deploy/production/rover-nameserver.example.yml)
- [`deploy/production/rover-gateway.example.yml`](../deploy/production/rover-gateway.example.yml)
- [`deploy/production/admin-application.example.yml`](../deploy/production/admin-application.example.yml)

本地 Docker 一键起见 [`deploy/docker`](../deploy/docker/README.md)，**仅演示**，勿把 demo token 直接上公网。

单机 systemd 启动顺序与 unit 示例：[`deploy/systemd/README.md`](../deploy/systemd/README.md)。

## 最小安全配置

1. Gateway、Nameserver 的业务/协议 token 使用随机非空值。
2. Gateway admin token 与 Nameserver admin token 使用不同随机值；Admin 携带对应管理 token。
3. 管理端口只允许运维网段访问，Admin 不直接暴露公网。
4. HTTP/TCP 前放置 TLS 终止、ACL 或 VPN；token 本身不加密链路。Gateway 到上游默认是明文 HTTP。调用方 HTTPS 在反向代理上终止。上游地址必须是 `https://` 时，再把 `rover.gateway.proxy.outbound` 设为 `jdk` 并重启；吞吐会回到 JDK 出站那一档。
5. 关闭不使用的 HTTP Registration API 和 CORS。
6. 正式机开启严格安全：空 token 直接拒绝启动。

```bash
export ROVER_STRICT_SECURITY=true
# 或：java -Drover.strictSecurity=true -jar ...
```

未开启时，空 token 仍可启动，但会打醒目 WARN（本地调试友好）。

```yaml
rover:
  nameserver:
    bindHost: 10.0.10.11
    manageBindHost: 10.0.10.11
    token: "replace-with-a-long-protocol-token"
    adminToken: "replace-with-a-different-admin-token"
    clientApiEnabled: false
  gateway:
    server:
      bindHost: 10.0.10.12
    adminToken: "replace-with-the-gateway-admin-token"
    cors:
      enabled: false
```

## 启动顺序与故障窗口

1. **先 Nameserver，再 Gateway，再业务 / Admin。**
2. Nameserver 短暂不可用时：Gateway 对**已缓存**的服务仍可转发；订阅恢复与周期对账后实例列表再对齐。
3. Nameserver **进程崩溃且内存注册表丢失**后：业务需重新注册；这是单机内存模型的明确边界，不是静默 HA。

## 探活

| 端点 | 用途 |
| --- | --- |
| `GET /_manage/health` | 轻量 UP；需管理 token（与其它管理口相同） |
| `GET /_manage/status` | 更详细（路由数、实例数等） |

```bash
curl -fsS -H "X-Rover-Admin-Token: YOUR_TOKEN" http://NS_HOST:8889/_manage/health
curl -fsS -H "X-Rover-Admin-Token: YOUR_TOKEN" http://GW_HOST:80/_manage/health
```

## 日志怎么开（排障）

| 级别 | 默认会看到 | 排障时 |
| --- | --- | --- |
| INFO | 启动监听、热更新、鉴权失败、上游错误、协议错误 | 保持 |
| DEBUG | 访问完成日志、路由命中、Nameserver 客户端连/断 | 临时打开对应 logger |

示例（Logback）：

```xml
<logger name="com.rover.gateway.core.filter.AccessLogFilter" level="DEBUG"/>
<logger name="com.rover.gateway.core.filter.RouteAndProxyFilter" level="DEBUG"/>
<logger name="com.rover.nameserver.core.server.NameserverServerHandler" level="DEBUG"/>
```

关掉访问日志过滤器：`rover.gateway.filters.accessLog: false`。

## 升级前检查

- 先跑备份脚本：`./deploy/scripts/backup-overlays.sh`（或指向你的 config 目录）
- 备份 `config/*overlay.json` 和静态路由配置。
- 先在同版本 Gateway/Nameserver 上验证 Admin API / `_manage/health`。
- 滚动升级时确保协议 token 和管理 token 不变。
- 升级后检查 `/api/overview`、路由数量、健康实例数和错误率。
- 发布冒烟：`./deploy/scripts/smoke-compose.sh`（需本机 Docker）。
