# 生产部署示例

Rover-Suite 当前定位是轻量单机或小规模内网部署。生产部署至少应把业务端口、注册协议端口、管理端口和 Admin
控制台分到明确的网络边界内。

## 最小安全配置

1. Gateway、Nameserver 的业务/协议 token 使用随机非空值。
2. Gateway admin token 与 Nameserver admin token 使用不同随机值；Admin 只保存管理 token。
3. 管理端口只允许运维网段访问，Admin 不直接暴露公网。
4. HTTP/TCP 前放置 TLS 终止、ACL 或 VPN；token 本身不加密链路。
5. 关闭不使用的 HTTP Registration API 和 CORS。

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

参见 [`deploy/docker`](../deploy/docker/README.md) 的本地一键启动示例。生产环境请替换 token、镜像 tag、端口映射和
bind/ACL 策略。

## 升级前检查

- 备份 `config/*overlay.json` 和静态路由配置。
- 先在同版本 Gateway/Nameserver 上验证 Admin API。
- 滚动升级时确保协议 token 和管理 token 不变。
- 升级后检查 `/api/overview`、路由数量、健康实例数和错误率。
