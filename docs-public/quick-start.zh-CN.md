# 快速上手

[English](./quick-start.md) · [文档索引](./README.md)

本指南会跑通一条完整请求链路：

```text
curl :8080/api/hello
  → Rover-Gateway :8080
  → demo-service :8081

demo-service → Rover-Nameserver :8888（TCP 注册）
```

这个 Java 演示不需要开启 `8889` 上的 HTTP Registration API。

## 1. 环境要求

- JDK 17+
- Maven 3.6+
- `curl`
- 3 个终端窗口

只有运行可选的前端测试面板时才需要 Node.js。

## 2. 获取并构建源码

```bash
git clone https://github.com/zzl-qz/Rover-Suite-.git roverSuite
cd roverSuite
mvn clean install -DskipTests
```

这里要使用 `install`，不只是 `package`。项目当前使用尚未发布到公共 Maven 仓库的
`1.0.0-SNAPSHOT`，demo 或其他本地项目需要从本机 Maven 仓库解析依赖。

## 3. 配置本机监听与 Gateway 8080 端口

内置配置有意采用“零配置优先”：默认监听所有网卡且鉴权为空，适合本地或可信网络快速启动。
本指南为了避免联调时意外对外暴露，复制两份完整配置并将监听地址收紧到回环网卡；可信网络环境也可以自行保留默认监听方式。

```bash
mkdir -p config
cp rover-gateway-bootstrap/src/main/resources/rover-gateway.yml config/rover-gateway.yml
cp rover-nameserver-bootstrap/src/main/resources/rover-nameserver.yml config/rover-nameserver.yml
```

先确保 `config/rover-nameserver.yml` 中包含：

```yaml
rover:
  nameserver:
    bindHost: 127.0.0.1
    manageBindHost: 127.0.0.1
```

再在 `config/rover-gateway.yml` 中设置回环监听与 `8080`：

```yaml
rover:
  gateway:
    port: 8080
    server:
      bindHost: 127.0.0.1
    discovery:
      type: nameserver
      nameserver:
        address: 127.0.0.1:8888
    routes:
      - id: demo-api
        businessPrefix: /api
        serviceName: demo-service
        stripPrefix: ""
```

Rover 会优先加载 `./config/rover-*.yml`。应把外部 YAML 视为对应进程的完整启动配置，避免假设它会与内置文件按文本逐项合并。

## 4. 启动三个进程

以下命令都在仓库根目录执行。

终端 1 —— Nameserver：

```bash
java -jar rover-nameserver-bootstrap/target/rover-nameserver-bootstrap-1.0.0-SNAPSHOT.jar
```

终端 2 —— demo 业务服务：

```bash
java -jar rover-gateway-test/backend/target/rover-demo-1.0.0-SNAPSHOT.jar
```

等待 Spring Boot 输出启动成功日志。Web Server ready 后，Starter 会把 `demo-service` 注册为
`127.0.0.1:8081`。

终端 3 —— Gateway：

```bash
java -jar rover-gateway-bootstrap/target/rover-gateway-bootstrap-1.0.0-SNAPSHOT.jar
```

## 5. 验证请求链路

Nameserver 内置配置为了本地兼容默认留空 `adminToken`，所以下面两个只读验收请求无需请求头：

```bash
curl -sS http://127.0.0.1:8889/_manage/status
curl -sS http://127.0.0.1:8889/_manage/instances
```

实例列表中应该能看到 `demo-service`。然后再验证对外请求链路：

```bash
curl -i http://127.0.0.1:8080/api/hello
```

收到 demo 返回的 `2xx` 响应，就说明服务注册、Gateway 发现、路由匹配和反向代理整条链路已经跑通。

也可以从日志中确认：

- Nameserver 已监听 TCP `8888` 与 HTTP `8889`。
- `demo-service` 在应用 ready 后注册成功。
- Gateway 发现模块已连接 `127.0.0.1:8888`。

## 6. 停止

先对 demo 按 `Ctrl+C`。正常的 Spring 关闭流程会尽力注销实例，然后再停止 Gateway 和 Nameserver。
Nameserver 会立即删除已注销实例；当前 Gateway 对最后一个实例的空快照带保护，最迟会在下一次查询对账时清空本地缓存，
默认上限约为 `reconcileIntervalMs`（30 秒）。

## 首次启动排障

| 现象 | 检查项 |
| :--- | :--- |
| Gateway 端口绑定失败 | 确认外部 Gateway 配置使用 `8080`，而不是内置默认值 `80`。 |
| Gateway 提示无可用实例 | 先启动 Nameserver，再确认 demo 与 Gateway 都使用 `127.0.0.1:8888`。Java 服务每 5 秒固定重试；如果 Gateway 先于 Nameserver 启动，当前版本最迟在下一次 30 秒对账时补齐发现，也可以直接重启 Gateway。 |
| 匹配不到路由 | 检查 `businessPrefix: /api`、`serviceName: demo-service`，并确认没有遗留的 `config/routes.overlay.json` 覆盖 YAML 路由。 |
| Gateway 连接业务服务被拒绝 | `rover.nameserver.host` 需要是 Gateway 可达地址。只有三个进程都在同一台机器上时才能使用 `127.0.0.1`。 |
| 鉴权失败 | Nameserver、Starter 与 Gateway 发现需要使用同一个协议 token。参见[使用指南](./user-guide.zh-CN.md)。 |

后续可阅读：

- [使用指南](./user-guide.zh-CN.md)
- [服务注册指南](./service-registration.zh-CN.md)
- [架构说明](./architecture.zh-CN.md)
- [二次开发指南](./development-guide.zh-CN.md)
