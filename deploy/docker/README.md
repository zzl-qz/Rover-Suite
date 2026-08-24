# Docker Compose 本地一键启动

需要 Docker Desktop 24+ 和 Compose v2。此示例用于**本地验收**，启动四个容器：Nameserver、Gateway、Admin 和 demo
服务。默认只发布本机端口，配置中的 token **仅用于演示**，生产环境必须替换。

```bash
cd deploy/docker
docker compose up --build
```

访问：

- Admin: <http://127.0.0.1:9090>
- Gateway demo: <http://127.0.0.1:8080/api/hello>
- Nameserver HTTP: <http://127.0.0.1:8889>

停止并清理：

```bash
docker compose down
```

## 姿态说明（必读）

| 项 | 本 Compose 演示 | 小团队内网生产 |
| --- | --- | --- |
| token | 固定 demo 串 | 随机长串，见 [`../production/`](../production/) |
| bind | 容器内 `0.0.0.0` | 内网 IP / 收紧网卡 |
| CORS | 开着方便本机 Admin | 默认关 |
| HTTP Registration API | 演示开启 | 无需要则关 |
| 端口映射 | 本机调试用 | 管理口 / Admin **不要**映射公网 |
| 严格安全 | 未开 | `ROVER_STRICT_SECURITY=true` |

**不要把本示例直接用于公网。** 生产请复制 [`../production/`](../production/) 样例，并阅读
[`docs-public/production-deployment.zh-CN.md`](../../docs-public/production-deployment.zh-CN.md)。

## 本机验收脚本

```bash
# 仓库根目录
./deploy/scripts/smoke-compose.sh
# 排障时保留容器：SMOKE_KEEP=1 ./deploy/scripts/smoke-compose.sh
```

## 小团队限额压测（无 Admin）

测的是 **Gateway + Nameserver + demo**，故意限 CPU/内存，模拟「机器不太够」；**不启 Admin**，避免观察面抢资源。

```bash
brew install hey   # 只需一次
# 主叙事：约 4C8G 档限额
./deploy/scripts/bench-small-team.sh
# 穷机对照：约 2C4G 档
BENCH_PROFILE=tiny ./deploy/scripts/bench-small-team.sh
# 测完先别拆栈：BENCH_KEEP=1 ./deploy/scripts/bench-small-team.sh
```

限额文件：

- [`docker-compose.perf-small.yml`](./docker-compose.perf-small.yml)
- [`docker-compose.perf-tiny.yml`](./docker-compose.perf-tiny.yml)

脚本会打 **两路对照**：经 Gateway（8080）与 **直连 demo**（8082，不经 Gateway/NS），同样 `hey -z 30s -c 50`；建议各重复 3 轮后再写文档。

**完整环境说明、三轮明细、结论与对外表述**见 [`docs-public/troubleshooting-performance.zh-CN.md`](../../docs-public/troubleshooting-performance.zh-CN.md) 的「性能边界」章节（含 Mac M5 Pro、Docker Desktop 16GB、small 限额等前提）。

升级前备份配置：

```bash
./deploy/scripts/backup-overlays.sh deploy/docker/config
```
