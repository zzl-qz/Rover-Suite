# systemd 单机部署示例

适合小团队一台机器上跑 Nameserver + Gateway。

1. 安装 JDK 17+，创建用户与目录（示例）：

```bash
sudo useradd -r -s /usr/sbin/nologin rover || true
sudo mkdir -p /opt/rover/config
sudo chown -R rover:rover /opt/rover
```

2. 放入启动 jar（从 `mvn -DskipTests package` 产物复制），并把
   [`../production/`](../production/) 样例改好后放到 `/opt/rover/config/`。

3. 安装 unit：

```bash
sudo cp deploy/systemd/rover-nameserver.service.example /etc/systemd/system/rover-nameserver.service
sudo cp deploy/systemd/rover-gateway.service.example /etc/systemd/system/rover-gateway.service
# 按实际 jar 路径改 ExecStart
sudo systemctl daemon-reload
sudo systemctl enable --now rover-nameserver
sudo systemctl enable --now rover-gateway
```

## 启动顺序

| 顺序 | 进程 | 说明 |
| --- | --- | --- |
| 1 | Nameserver | 注册与推送；挂了之后实例租约会过期 |
| 2 | Gateway | `Requires=rover-nameserver`；Nameserver 短暂不可用时，Gateway **仍可用本地实例缓存转发**，对账恢复后自动跟上 |
| 3 | 业务服务 / Admin | 业务向 Nameserver 注册；Admin 可选，只放运维网 |

## 探活

管理口开启且配置了 adminToken 时，探活需带请求头：

```bash
curl -fsS -H "X-Rover-Admin-Token: YOUR_TOKEN" \
  http://127.0.0.1:8889/_manage/health
curl -fsS -H "X-Rover-Admin-Token: YOUR_TOKEN" \
  http://127.0.0.1:80/_manage/health
```

期望：`{"status":"UP","component":"..."}`。更详细用 `/_manage/status`。
