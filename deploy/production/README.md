# 小团队单机 / 内网生产配置样例

本目录是**可抄改的生产姿态模板**，不是 Docker Compose 演示配置。

- 演示一键起：[`../docker/`](../docker/)（固定 demo token，勿直接上公网）
- 部署说明：[`../../docs-public/production-deployment.zh-CN.md`](../../docs-public/production-deployment.zh-CN.md)

## 用法

1. 复制样例到运行目录（避免提交真实 token）：

```bash
mkdir -p config
cp deploy/production/rover-nameserver.example.yml config/rover-nameserver.yml
cp deploy/production/rover-gateway.example.yml config/rover-gateway.yml
# Admin（可选）
cp deploy/production/admin-application.example.yml /path/to/admin/config/application.yml
```

2. 把所有 `REPLACE_*` 换成足够长的随机串；协议 token 与管理 token 分开。当前同一 Admin 同时管理两端时，管理 token 需与其 `admin-token` 一致；
   Gateway 连 Nameserver 的 `discovery.nameserver.token` 需要与 Nameserver `token` 一致。

3. 把 `bindHost` / `manageBindHost` 改成内网 IP（示例里是 `10.0.10.x`）。

4. 正式机建议开启严格安全（空 token 直接拒绝启动）：

```bash
export ROVER_STRICT_SECURITY=true
# 或 JVM：-Drover.strictSecurity=true
```

5. Nameserver 先起，再起 Gateway；Admin 只放运维网段。

6. 生产 Admin 配置示例启用 Prometheus 指标，并将 Actuator 管理端口绑定到 `127.0.0.1`。该独立端口不经过控制台登录会话认证，仅供同机 Prometheus/受控代理抓取；远程抓取应使用认证代理或安全隧道，不要把 `/actuator/prometheus` 管理端口发布到公网或业务网卡。加载 `prometheus-agent.rules.yml` 前，按本机队列配置和监控平台的标签/路由规范调整阈值与通知目标。

相关：

- 探活：`GET /_manage/health`（需管理 token）
- systemd 示例：[`../systemd/README.md`](../systemd/README.md)
- 备份：`../scripts/backup-overlays.sh` 只包含 overlay；Admin 的 `log-store-path` 目录、模型配置和主密钥需另行备份，记录库复制前停 Admin。
- 冒烟：`../scripts/smoke-compose.sh`
- Agent 告警规则模板：[`prometheus-agent.rules.yml`](prometheus-agent.rules.yml)；覆盖队列接近上限、任务失败/拒绝、模型故障与队列停滞，不替代值班响应人和处置手册。
