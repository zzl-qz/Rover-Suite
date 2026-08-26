# 小团队上线 10 条

面向 **单机 / 小规模内网** 的最短上线清单。详细步骤见[生产部署](./production-deployment.zh-CN.md)；样例 YAML 见 [`deploy/production`](../deploy/production/README.md)。

## 适用边界

| 是 | 不是 |
| --- | --- |
| 小团队、私有网络、单机或少量节点 | Nameserver 集群 HA / 跨机房注册表 |
| 内存注册 + 推送与对账 | 进程挂了注册表自动从磁盘恢复（需业务重注册） |
| Token + 网络隔离保护管理面 | 开箱即用的完整鉴权 / 熔断平台 |
| Overlay 热更新可备份脚本 | 替代 APISIX / Nacos 全家桶 |
| 调用方 HTTPS 在反代终止，网关到上游默认 HTTP | 默认出站对上游做 TLS，或由网关进程终止 HTTPS |

## 上线 10 条

1. **抄生产样例，避免抄 demo。** 用 `deploy/production/*.example.yml`，改掉所有 token 与 bindHost。
2. **开启严格安全。** `ROVER_STRICT_SECURITY=true`（或 `-Drover.strictSecurity=true`），空 token 会直接拒绝启动。
3. **管理口与业务口分开。** Nameserver 协议口 / 管理口、Gateway 业务口、Admin 控制台分网段；Admin 不挂公网。
4. **业务 token ≠ 管理 token。** Gateway admin、Nameserver admin、协议 token 各用不同长随机串。
5. **关掉不用的口子。** 如 `clientApiEnabled: false`、CORS `enabled: false`（除非前端真要跨域）。
6. **启动顺序固定。** Nameserver → Gateway → 业务 / Admin；systemd 示例见 `deploy/systemd/`。
7. **探活进监控。** `GET /_manage/health` + `X-Rover-Admin-Token`；Compose 可用 `deploy/scripts/smoke-compose.sh`。
8. **Overlay 要备份。** 热更新落盘目录定期跑 `deploy/scripts/backup-overlays.sh`；备份目录勿提交 git。
9. **日志默认够用。** 访问日志默认 debug + `filters.accessLog`；排障时再临时开 DEBUG，避免长期全量 INFO 刷盘。
10. **接受内存边界。** Nameserver 进程停止后，实例表会清空。恢复依赖业务重注册或滚动重启，不能按“静默 HA”理解。

做完 1～10 仍卡 404/502/注册，走[故障排查](./troubleshooting.zh-CN.md)。发布版本前再过一遍[发布检查清单](./release-checklist.zh-CN.md)。
