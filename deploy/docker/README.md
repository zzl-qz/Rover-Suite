# Docker Compose 本地一键启动

需要 Docker Desktop 24+ 和 Compose v2。此示例用于本地验收，启动四个容器：Nameserver、Gateway、Admin 和 demo
服务。默认只发布本机端口，配置中的 token 仅用于演示，生产环境必须替换。

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

不要把本示例直接用于公网：它是演示用固定 token、容器内全网卡监听和开发 CORS 配置。生产部署请参考
`docs-public/production-deployment.zh-CN.md`。
