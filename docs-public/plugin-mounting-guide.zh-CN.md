# 插件挂载操作手册

本文只回答两个问题：项目有多少种可直接挂载的插件，以及用户如何亲自验证它们是否生效。

## 1. 先记住结论

| 统计口径 | 数量 |
| --- | ---: |
| 可直接放入 Gateway `plugins` 目录的插件类型 | **2 种** |
| 每种插件的挂载方式 | **2 种** |
| 用户可使用的实际挂载方式 | **4 种** |
| 底层插件目录和加载器 | **1 套** |

| 插件类型 | 方式 1：SPI 自动发现 | 方式 2：显式类名/名称 |
| --- | --- | --- |
| `Filter` | JAR + `META-INF/services/com.rover.common.spi.filter.Filter` | `rover.gateway.filters.classes` 填全限定类名 |
| `LoadBalancer` | JAR + `META-INF/services/com.rover.common.spi.loadbalance.LoadBalancer`，配置 `name()` | `rover.gateway.loadbalance.strategy` 填全限定类名 |

四种方式共用一个 `pluginDir`，默认是 Gateway 工作目录下的 `plugins`。插件运行在 Gateway JVM 内，不是独立进程。

注意两者在链路中的角色不同：`Filter` 会作为一个节点加入过滤器链并按 `getOrder()` 执行；`LoadBalancer` 不会作为 `Filter` 节点出现，而是由链路末端的 `RouteAndProxyFilter` 调用，用来从注册中心或静态路由返回的候选实例中选择一个上游。

请求处理关系可以理解为：

```text
请求
  → 内置/插件 Filter 链
  → RouteAndProxyFilter（终端 Filter）
      → ServiceDiscovery 或静态路由获取候选实例
      → LoadBalancer 插件选择实例
      → HTTP 转发
```

`ServiceDiscovery`、注册传输适配器和 Nacos 当前是源码级扩展，不属于这 4 种直接挂载方式。

## 2. 用户操作流程

如果已经有插件 JAR，直接从第 2 步开始；如果还没有插件，先按[插件开发与接入](./plugin-development.zh-CN.md)编译一个实现 `Filter` 或 `LoadBalancer` 的 JAR。

### 2.1 准备插件目录

在 Gateway 工作目录执行：

```powershell
New-Item -ItemType Directory -Force plugins | Out-Null
Copy-Item C:\path\to\your-plugin.jar .\plugins\
```

Linux/macOS：

```bash
mkdir -p plugins
cp /path/to/your-plugin.jar ./plugins/
```

不要把 `rover-common` 或 `rover-gateway-core` 打包进插件 JAR；它们由 Gateway 在运行时提供。

### 2.2 配置挂载方式

编辑 Gateway 的 `rover-gateway.yml`。以下配置展示四种入口，实际使用时按插件类型选择其中一种：

```yaml
rover:
  gateway:
    filters:
      enabled: true
      pluginDir: plugins
      # Filter 显式类名方式；SPI 方式不需要填这里
      classes:
        - com.example.rover.RequestTagFilter
    loadbalance:
      # LoadBalancer SPI 方式填 name()，例如 first_healthy；
      # FQCN 方式则填写 com.example.rover.FirstHealthyLoadBalancer
      strategy: first_healthy
```

相对路径 `pluginDir: plugins` 按进程当前工作目录解析。也可以填写绝对路径。

### 2.3 启动 Gateway

在 Gateway 工作目录启动：

```powershell
java -jar ..\rover-gateway-bootstrap\target\rover-gateway-bootstrap-1.0.0-SNAPSHOT.jar
```

Linux/macOS：

```bash
java -jar ../rover-gateway-bootstrap/target/rover-gateway-bootstrap-1.0.0-SNAPSHOT.jar
```

## 3. 验证 Filter

另开终端访问一个已配置路由：

```bash
curl -i http://127.0.0.1:80/your-route
```

检查 Gateway 日志：

SPI 自动发现应看到：

```text
Loaded ... filter plugin jar(s)
Discovered plugin filter via SPI: name=...
Gateway filter chain ready
```

显式类名方式应看到：

```text
Loaded configured filter: name=...
```

要证明 Filter 真的执行，在插件中写入请求属性、响应头或业务日志，再发起一次真实请求观察结果；仅看到 JAR 被扫描只能证明“加载”，不能证明“执行”。

## 4. 验证 LoadBalancer

### 4.1 SPI 按名称加载

将 `strategy` 改成插件 `name()` 返回值，例如：

```yaml
rover:
  gateway:
    loadbalance:
      strategy: first_healthy
```

JAR 中必须存在：

```text
META-INF/services/com.rover.common.spi.loadbalance.LoadBalancer
```

文件内容每行一个实现类全限定名。启动日志应类似：

```text
使用插件负载均衡器: name=first_healthy, class=com.example.rover.FirstHealthyLoadBalancer
```

### 4.2 按全限定类名加载

不使用 LoadBalancer SPI 文件，把配置改为：

```yaml
rover:
  gateway:
    loadbalance:
      strategy: com.example.rover.FirstHealthyLoadBalancer
```

重启后日志应类似：

```text
使用自定义负载均衡器: com.example.rover.FirstHealthyLoadBalancer
```

这表示 Gateway 已从插件 ClassLoader 反射创建实现类。

## 5. 验证策略是否真的参与请求

准备两个端口不同、响应中包含自身端口号的后端，例如 `8082` 和 `8083`。配置静态多上游：

```yaml
rover:
  gateway:
    discovery:
      type: static
    routes:
      - id: plugin-lb
        businessPrefix: /plugin-lb
        targetUrls:
          - http://127.0.0.1:8082
          - http://127.0.0.1:8083
        stripPrefix: /plugin-lb
```

请求：

```bash
curl http://127.0.0.1:80/plugin-lb/api/info
```

连续请求并记录返回的端口，再切换另一个插件策略并重启 Gateway。返回实例选择应按新策略变化；这样同时验证了“插件被加载”和“插件实际参与请求决策”。

## 6. 四种方式的检查清单

- Filter SPI：JAR 在 `pluginDir`，SPI 文件名和类名完全匹配，日志出现 `Discovered plugin filter via SPI`。
- Filter 显式类名：`filters.classes` 填完整类名，类为 `public` 且有 public 无参构造函数。
- LoadBalancer SPI：SPI 文件存在，`strategy` 等于实现类 `name()`，日志出现 `使用插件负载均衡器`。
- LoadBalancer FQCN：`strategy` 填完整类名，类为 `public` 且有 public 无参构造函数，日志出现 `使用自定义负载均衡器`。

修改 JAR、SPI 文件或类路径后必须重启 Gateway；当前没有持续监听插件目录的 watcher。插件在 Gateway JVM 内执行，禁止在 Netty 事件循环上做阻塞 I/O、远程调用或无限重试。
