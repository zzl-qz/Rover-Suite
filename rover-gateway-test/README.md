# Gateway 测试套件

> 本目录只用于验证 Rover Gateway 与 Nameserver，不是生产业务模板。

首次使用建议先按公开[快速上手](../docs-public/quick-start.zh-CN.md)跑通单实例链路，再使用本页验证多实例与 Gateway 行为。
如果要验证外部插件 JAR 的真实挂载，请看公开的[插件挂载指南](../docs-public/plugin-mounting-guide.zh-CN.md)。

## 目录

```text
rover-gateway-test/demo/
├── backend/    # Spring Boot 测试服务，已接入 Rover Starter
├── frontend/   # Vue 3 测试面板
└── README.md
```

## 1. 构建与配置

在仓库根目录执行：

```bash
mvn clean install -DskipTests
mkdir -p config
cp rover-gateway-bootstrap/src/main/resources/rover-gateway.yml config/rover-gateway.yml
cp rover-nameserver-bootstrap/src/main/resources/rover-nameserver.yml config/rover-nameserver.yml
```

将 `config/rover-gateway.yml` 中的 Gateway `port` 改为你需要的值（仓库默认是 `80`；本 README 示例也可继续用 `8080`）、`server.bindHost` 改为 `127.0.0.1`，并保留内置的 `demo-api` 路由和 Nameserver 发现配置。
将 `config/rover-nameserver.yml` 的 `bindHost` 和 `manageBindHost` 都改为 `127.0.0.1`。这样本地空 token 管理接口不会暴露给局域网。

## 2. 启动基础组件

终端 1 —— Nameserver：

```bash
java -jar rover-nameserver-bootstrap/target/rover-nameserver-bootstrap-1.0.0-SNAPSHOT.jar
```

终端 2 —— Gateway：

```bash
java -jar rover-gateway-bootstrap/target/rover-gateway-bootstrap-1.0.0-SNAPSHOT.jar
```

## 3. 启动一个或多个 backend

单实例：

```bash
java -jar rover-gateway-test/demo/backend/target/rover-demo-1.0.0-SNAPSHOT.jar
```

多实例负载均衡验证（每条命令使用独立终端）：

```bash
java -jar rover-gateway-test/demo/backend/target/rover-demo-1.0.0-SNAPSHOT.jar --server.port=8081
java -jar rover-gateway-test/demo/backend/target/rover-demo-1.0.0-SNAPSHOT.jar --server.port=8082
java -jar rover-gateway-test/demo/backend/target/rover-demo-1.0.0-SNAPSHOT.jar --server.port=8083
```

Starter 在每个 Web Server ready 后将实际端口注册到 `127.0.0.1:8888`。这组地址只适合所有进程都在本机的测试环境。

## 4. 验收

```bash
curl -sS http://127.0.0.1:8889/_manage/instances
# 下面端口以你的 Gateway 配置为准：仓库默认 80，示例常用 8080
curl -sS http://127.0.0.1/api/hello
curl -sS 'http://127.0.0.1/api/echo?msg=rover'
curl -sS http://127.0.0.1/api/health
```

多次请求 `/api/hello`，观察 JSON 中的 `port` 字段，可以验证当前负载均衡策略。

## 5. 可选前端面板

需要 Node.js 与 npm：

```bash
cd rover-gateway-test/demo/frontend
npm install
npm run dev
```

访问 `http://127.0.0.1:3000`。面板默认使用 `http://localhost`（端口 80），也可在页面中修改 Gateway 地址。

面板可用于验证：

- GET/POST 代理和请求头传递。
- 多实例端口分布。
- 并发请求与响应耗时。
- 上游延迟、404 与模拟错误。

## 注意事项

- 本测试使用本地空 token 配置，不是生产安全示例。
- 如果 YAML 路由修改不生效，检查 `config/routes.overlay.json` 是否在覆盖路由列表。
- 完整生产配置、鉴权和部署边界见[使用指南](../docs-public/user-guide.zh-CN.md)。
