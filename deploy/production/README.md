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

2. 把所有 `REPLACE_*` 换成足够长的随机串；Gateway / Nameserver 的 **adminToken 建议不同**；
   Gateway 连 Nameserver 的 `discovery.nameserver.token` 需要与 Nameserver `token` 一致。

3. 把 `bindHost` / `manageBindHost` 改成内网 IP（示例里是 `10.0.10.x`）。

4. 正式机建议开启严格安全（空 token 直接拒绝启动）：

```bash
export ROVER_STRICT_SECURITY=true
# 或 JVM：-Drover.strictSecurity=true
```

5. Nameserver 先起，再起 Gateway；Admin 只放运维网段。

相关：

- 探活：`GET /_manage/health`（需管理 token）
- systemd 示例：[`../systemd/README.md`](../systemd/README.md)
- 备份：`../scripts/backup-overlays.sh`
- 冒烟：`../scripts/smoke-compose.sh`
