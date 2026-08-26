# 插件挂载操作手册

这篇手册帮助你把外部插件 JAR 接入 Gateway，并确认它是否参与请求处理。建议先看第 1 节了解整体结论，再按第 2 节开始操作。

## 1. 先记住结论

| 统计口径 | 数量 |
| --- | ---: |
| 可直接放入 Gateway `plugins` 目录的扩展点 | **2 个** |
| 每个扩展点支持的装配方式 | **2 条** |
| 插件接入路径 | **2 类插件 × 2 种装配方式** |
| 底层插件目录和加载器 | **1 套** |

| 扩展点 | 方式 1：SPI 自动发现 | 方式 2：显式类名/名称 |
| --- | --- | --- |
| 用户 Filter 插件 | JAR + `META-INF/services/com.rover.common.spi.filter.Filter` | `rover.gateway.filters.classes` 填全限定类名 |
| 负载均衡策略插件 | JAR + `META-INF/services/com.rover.common.spi.loadbalance.LoadBalancer`，配置 `name()` | `rover.gateway.loadbalance.strategy` 填全限定类名 |

这两个扩展点共用一个 `pluginDir`。不配置时，默认使用 Gateway 工作目录下的 `plugins`。这里的“2 类插件 × 2 种装配方式”表示：用户 Filter 插件和负载均衡策略插件，都可以通过 SPI 自动发现或显式配置接入。它不是四种不同插件类别。插件运行在 Gateway JVM 内，不是独立进程。

两类扩展点在链路中的角色不同。用户 Filter 插件会作为节点加入过滤器链，并按 `getOrder()` 执行。负载均衡策略插件不是 `Filter` 接口实现。它是固定终端环节 `RouteAndProxyFilter` 里的策略插槽，用来从 Nameserver 或静态路由返回的候选实例中选择一个上游。

请求处理关系可以理解为：

```text
请求
  → 内置/插件 Filter 链
  → RouteAndProxyFilter（终端 Filter）
      → ServiceDiscovery 或静态路由获取候选实例
      → LoadBalancer 策略插件选择实例
      → HTTP 转发
```

`ServiceDiscovery`、注册传输适配器和 Nacos 当前是源码级扩展，不属于这两个 `plugins` 目录扩展点。

Gateway 还提供一个内置固定阶段 `RateLimitFilter`。它控制 Gateway 本地限流能力。不配置时默认关闭。你可以在 `rover.gateway.rateLimit` 中选择 `token_bucket` 或 `sliding_window`。如果需要按用户、租户或共享存储限流，建议关闭内置限流，并挂载自己的用户 Filter 插件。进程内熔断走 `rover.gateway.circuitBreaker`，挂在选点和转发收尾，不是独立 Filter。连不上换台走 `rover.gateway.retry.enabled`。

对于 Filter，SPI 自动发现适合“JAR 放入后，登记的实现全部加载”。这种方式无需在 YAML 重复维护类名。显式类名适合“JAR 可以存在，但由部署配置决定启用哪些类”。后者更灵活，但增加、删除或改名后，需要改 `filters.classes` 并重启 Gateway。

对于 LoadBalancer 也类似。SPI `name()` 更适合固定发布的策略名。全限定类名更适合临时验证、灰度切换，或不想维护 SPI 文件的场景。无论哪种方式，`gateway.loadbalance.strategy` 都属于启动/插件装配配置，不由 Admin 修改。这样可以避免 Admin 覆盖本地 YAML，导致“配置明明写了自定义策略，但运行时被管理台改回内置策略”的问题。

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

不建议把 `rover-common` 或 `rover-gateway-core` 打包进插件 JAR。它们由 Gateway 在运行时提供。

`plugins/` 是运行时文件夹，不是 Java 包。下图中生成的 `demo-block-plugin-1.0.0.jar` 已被放入 Gateway
工作目录下的 `plugins/`，随后 Gateway 才会在启动时扫描它；实际发现与装配日志见[第 3 节](#3-验证-filter)。

### 2.2 配置装配方式

编辑 Gateway 的 `rover-gateway.yml`。下面的配置展示两个扩展点的两类装配方式。实际使用时，按扩展点选择其中一种即可。

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

`pluginDir` 控制 Gateway 从哪里扫描插件 JAR。不配置时默认使用 `plugins`。相对路径按 Gateway 进程当前工作目录解析，也可以填写绝对路径。

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

如果使用 SPI 自动发现，日志通常会出现：

```text
Loaded ... filter plugin jar(s)
Discovered plugin filter via SPI: name=...
Gateway filter chain ready
```

日志中同时出现 `Loaded ... filter plugin jar(s)`、`Discovered plugin filter via SPI` 和过滤器链中的插件类，表示
JAR、SPI 声明与 Filter 实现三者已经对应成功。

![Gateway 发现并装配 demo-block Filter](./images/plugin/filter-plugin-gateway-loaded.png)

如果使用显式类名方式，日志通常会出现：

```text
Loaded configured filter: name=...
```

显式类名方式不需要 SPI 文件。清除旧构建产物后，保留 JAR 并写入完整类名即可：

```yaml
rover:
  gateway:
    filters:
      enabled: true
      pluginDir: plugins
      classes:
        - org.example.rover.DemoBlockFilter
```

要确认 Filter 已经执行，可以在插件中写入请求属性、响应头或业务日志，再发起一次真实请求观察结果。仅看到 JAR 被扫描只能证明“加载”，不能证明“执行”。
下图中的“执行自定义 Filter”来自 `DemoBlockFilter`，证明请求已进入外部 Filter。

![请求实际进入 demo-block Filter](./images/plugin/filter-plugin-request-executed.png)

无 SPI 的实测示例中，YAML 的 `classes` 指向 `DemoBlockFilter`，请求日志同样出现“执行自定义 Filter”。

![无 SPI 的显式类名 Filter 实测](./images/plugin/filter-plugin-class-config-executed.png)

### 3.1 用户自定义限流 Filter 验证

如果要实现复杂业务限流，建议直接写普通 `Filter` 插件，不需要扩展内部 `RateLimiter` 算法 SPI。下面这个本地演示把
`DemoRateLimitFilter` 放进 `filters.classes`，让它按请求路径做每秒 2 次的本地限流。Gateway 启动后日志显示
`demo-rate-limit` 已进入过滤器链，说明它和其他用户 Filter 插件走的是同一套装配机制。

```yaml
rover:
  gateway:
    filters:
      enabled: true
      pluginDir: plugins
      classes:
        - org.example.rover.DemoRateLimitFilter
```

![用户自定义限流 Filter 装配成功](./images/plugin/filter-plugin-rate-limit-loaded.png)

连续请求同一路径时，超过插件自己的阈值后直接返回 `429`，证明限流逻辑不仅被加载，而且实际参与了请求处理。

![用户自定义限流 Filter 返回 429](./images/plugin/filter-plugin-rate-limit-rejected.png)

这类插件适合做 IP、用户、租户、灰度分组或 Redis 分布式限流。内置 `rover.gateway.rateLimit` 只做轻量本地保护。
业务差异较大的限流建议关闭内置限流，改用自定义 Filter 插件。

## 4. 验证 LoadBalancer

### 4.1 SPI 按名称加载

将 `strategy` 改成插件 `name()` 返回值，例如：

```yaml
rover:
  gateway:
    loadbalance:
      strategy: first_healthy
```

JAR 中需要存在：

```text
META-INF/services/com.rover.common.spi.loadbalance.LoadBalancer
```

文件内容每行一个实现类全限定名。启动日志通常类似：

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

重启后日志通常类似：

```text
使用自定义负载均衡器: com.example.rover.FirstHealthyLoadBalancer
```

这表示 Gateway 已从插件 ClassLoader 反射创建实现类。

本地已经验证过 `org.example.rover.DemoHighestPortLoadBalancer` 的全限定类名加载方式。JAR 放在 `E:\\roverSuite\\plugins`，YAML 中直接把
`strategy` 写成完整类名。连续请求命中自定义策略选择的后端，说明“JAR 扫描、类加载、策略实例化、请求决策”都已闭环。

![LoadBalancer 按全限定类名加载并参与请求](./images/plugin/loadbalancer-class-config-executed.png)

## 5. 验证策略是否参与请求

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

连续请求并记录返回的端口，再切换另一个插件策略并重启 Gateway。返回实例选择通常会按新策略变化。这样可以同时验证“插件被加载”和“插件实际参与请求决策”。

## 6. 两个扩展点的检查清单

- Filter SPI：JAR 在 `pluginDir`，SPI 文件名和类名完全匹配，日志出现 `Discovered plugin filter via SPI`。
- Filter 显式类名：`filters.classes` 填完整类名，类为 `public` 且有 public 无参构造函数。
- LoadBalancer SPI：SPI 文件存在，`strategy` 等于实现类 `name()`，日志出现 `使用插件负载均衡器`。
- LoadBalancer FQCN：`strategy` 填完整类名，类为 `public` 且有 public 无参构造函数，日志出现 `使用自定义负载均衡器`。

修改 JAR、SPI 文件或类路径后，需要重启 Gateway。当前没有持续监听插件目录的 watcher。插件运行在 Gateway 请求链路内，简单判断、打标、内存计数和上游选择通常开销很小。真正需要避免的是阻塞 I/O、无超时远程调用、无限重试和大对象日志。

## 7. 继续验证用户自定义能力

上面覆盖的是“把外部 JAR 挂进 Gateway”的两个扩展点及其两种装配方式。如果这些都已通过，插件挂载主链路就已经验证完成。

用户自定义限流已经验证通过：它不是新的扩展点，而是普通用户 Filter 插件的一种业务实现。

如果还要继续补边界，建议测 **插件动态配置**。它不是新的挂载方式，而是 `Filter` 或 `LoadBalancer` 可选实现 `ConfigurablePlugin` 后暴露的能力。适合验证：

- 插件是否能声明 `gateway.plugin.<namespace>.<key>` 配置项；
- Admin 是否能看到这些插件自己的配置项；
- 保存后 Gateway 是否立即调用插件的配置回调；
- 下一次真实请求是否按新配置执行。

如果插件没有实现 `ConfigurablePlugin`，Admin 的“插件配置”为空是正常现象；这不影响 Filter 或 LoadBalancer 本身被加载和执行。
