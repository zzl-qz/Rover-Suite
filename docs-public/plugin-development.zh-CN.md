# 插件开发与接入

Rover Gateway 的插件运行在 Gateway 进程内，目前提供两个可插拔扩展点：用户 Filter 插件和负载均衡策略插件。插件不是独立服务，也不是 Admin 插件。它会直接参与 Gateway 请求链路，所以建议和当前 Rover-Suite 版本保持一致。

这篇指南帮助你从零做一个可加载的插件。你会看到如何创建 JAR、声明 SPI、放入 Gateway、配置并验证效果。发布插件前，建议先在目标环境一致的 JDK 和 Rover-Suite 版本上验证。

## 扩展点与加载路径总览

当前可以直接把 JAR 放入 Gateway `plugins` 目录的插件扩展点有 **2 类**：用户 Filter 插件和负载均衡策略插件。每类插件都支持 **SPI 自动发现** 和 **显式配置** 两种装配方式，所以共有 4 条接入路径。这里统计的是接入路径，不是 4 类插件。

| 扩展点 | SPI 自动发现 | 显式类名加载 |
| --- | --- | --- |
| 用户 Filter 插件 | `META-INF/services/com.rover.common.spi.filter.Filter`，加载后按 `getOrder()` 组装过滤器链 | `rover.gateway.filters.classes` 填 Filter 全限定类名 |
| 负载均衡策略插件 | `META-INF/services/com.rover.common.spi.loadbalance.LoadBalancer`，通过 `name()` 配置选择 | `rover.gateway.loadbalance.strategy` 直接填写实现类全限定名 |

这两类插件共用同一个 `pluginDir` 和 `URLClassLoader`。负载均衡策略不会作为独立 `Filter` 节点出现。它是固定终端环节 `RouteAndProxyFilter` 中的策略插槽。`ServiceDiscovery`、注册传输适配器和 Nacos 当前属于源码级扩展，不会通过 `plugins` 目录直接挂载。EventBus 的 `ServiceLoader` 也不是本目录插件机制。

### Filter 的两种方式如何选择

| 方式 | 使用体验 | 适用场景 |
| --- | --- | --- |
| **SPI 自动发现** | 在 JAR 的 SPI 文件登记实现类即可；Gateway 会加载全部已登记的 Filter，YAML 不必逐个维护类名 | 希望“放入即启用”的独立能力，或该 JAR 内所有 Filter 都随环境一并加载 |
| **显式类名** | 不需要 SPI 文件；JAR 放入 `pluginDir` 后，再用 `filters.classes` 列出要启用的完整类名 | 一个 JAR 中有多个可选 Filter，或希望由部署配置精确决定哪些类生效 |

SPI 更省事，但登记的 Filter 都会被加载。显式类名更灵活，但新增、删除或改类名后，需要同步维护配置并重启 Gateway。

## 1. 选择扩展点

| 扩展点 | 用途 | 接入方式 |
| --- | --- | --- |
| `com.rover.common.spi.filter.Filter` | 在请求转发前后做鉴权、灰度标记、审计、业务限流等轻量处理 | SPI 自动发现，或在 `filters.classes` 中填写全限定类名 |
| `com.rover.common.spi.loadbalance.LoadBalancer` | 固定转发环节中的上游选择策略，从健康实例列表中选择一个上游实例 | SPI `name()` 选择，或直接填写实现类全限定名 |

Filter 的 `getOrder()` 越小越早执行。过滤器要继续执行后续链路时，需要调用 `chain.doFilter(context)`。读取鉴权头时可以使用 `context.requestHeader(name)`。拒绝请求时可以使用 `context.reject(status, body)`，然后返回已完成的 Future。Gateway 自带的本地限流可通过 `rover.gateway.rateLimit` 开启。按用户、租户或共享全局维度限流时，建议关闭内置限流，并实现自己的 Filter。进程内熔断走 `rover.gateway.circuitBreaker`，挂在选点和转发收尾，不是独立 Filter。连不上换台走 `rover.gateway.retry.enabled`，也不是独立 Filter。

## 2. 创建最小 Maven 插件项目

插件项目只需要在编译期依赖 `rover-common`。运行时 Gateway 已经提供这份依赖，所以不建议把 `rover-common` 或整套 Gateway 重新打进插件 JAR。也建议避免使用 shade 把 Rover 类复制一份。

```xml
<dependency>
  <groupId>com.rover</groupId>
  <artifactId>rover-common</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <scope>provided</scope>
</dependency>
```

如果当前版本尚未发布到 Maven 仓库，先在 Rover-Suite 根目录执行 `mvn -DskipTests install`，或把 `rover-common` 发布到团队内部仓库。插件的 Java 版本应与 Gateway 的 JDK 兼容（当前项目要求 JDK 17+）。

## 3. 实现插件类

### Filter 示例

```java
package com.example.rover;

import com.rover.common.spi.filter.Filter;
import com.rover.common.spi.filter.FilterChain;
import com.rover.common.spi.filter.RequestContext;
import java.util.concurrent.CompletableFuture;

public final class RequestTagFilter implements Filter {
    @Override
    public String getName() {
        return "request-tag";
    }

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public CompletableFuture<Void> doFilter(RequestContext context, FilterChain chain)
            throws Exception {
        context.setAttribute("plugin", "request-tag");
        return chain.doFilter(context);
    }
}
```

### LoadBalancer 示例

```java
package com.example.rover;

import com.rover.common.model.ServiceInstance;
import com.rover.common.spi.loadbalance.LoadBalanceContext;
import com.rover.common.spi.loadbalance.LoadBalancer;

public final class FirstHealthyLoadBalancer implements LoadBalancer {
    @Override
    public String name() {
        return "first_healthy";
    }

    @Override
    public ServiceInstance choose(LoadBalanceContext context) {
        return context.getInstances().isEmpty()
                ? null
                : context.getInstances().get(0);
    }
}
```

实现类需要是 `public`、可实例化，并提供无参构造函数。LoadBalancer 只会收到调用方已经筛选出的候选实例。没有实例时建议返回 `null`。通常不建议在插件中自行查询 Nameserver。

### 可选：让 Admin 动态配置插件

`Filter` 和 `LoadBalancer` 都可以选择实现 `ConfigurablePlugin`。Gateway 会把每个声明字段注册为
`gateway.plugin.<namespace>.<key>`，在 Admin 的“插件配置”分组中展示；保存时会先校验、立即调用插件回调，并写入
Gateway runtime overlay。

```java
import com.rover.common.spi.plugin.ConfigurablePlugin;
import com.rover.common.spi.plugin.PluginConfigProperty;
import com.rover.common.spi.filter.Filter;
import com.rover.common.spi.filter.FilterChain;
import com.rover.common.spi.filter.RequestContext;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class RequestTagFilter implements Filter, ConfigurablePlugin {
    private volatile boolean enabled = true;
    private volatile String tag = "request-tag";

    @Override public String configNamespace() { return "request-tag"; }

    @Override
    public List<PluginConfigProperty> configProperties() {
        return List.of(
                new PluginConfigProperty("enabled", "true", "是否写入请求标签",
                        List.of("true", "false"), false),
                new PluginConfigProperty("tag", "request-tag", "写入的标签值"));
    }

    @Override
    public void validateConfig(String key, String value) {
        if ("enabled".equals(key) && !"true".equalsIgnoreCase(value)
                && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("enabled 仅支持 true/false");
        }
        if ("tag".equals(key) && (value == null || value.isBlank())) {
            throw new IllegalArgumentException("tag 不能为空");
        }
    }

    @Override
    public void applyConfig(String key, String value) {
        switch (key) {
            case "enabled" -> enabled = Boolean.parseBoolean(value);
            case "tag" -> tag = value;
            default -> throw new IllegalArgumentException("未知插件配置：" + key);
        }
    }

    @Override
    public CompletableFuture<Void> doFilter(RequestContext context, FilterChain chain) {
        if (enabled) {
            context.setAttribute("plugin", tag);
        }
        return chain.doFilter(context);
    }
}
```

下面是一个真实的最小 Filter 插件：`DemoBlockFilter` 实现 `Filter`，以 `getOrder()` 决定顺序；示例中的
`System.out.println` 仅用于首次接入验证，正式插件应使用项目统一日志，并避免逐请求输出大量日志。

![最小 Filter 插件示例](./images/plugin/filter-plugin-filter-code.png)

插件字段的初始值建议与 `configProperties()` 中的默认值保持一致。`configNamespace()` 和字段 key 仅支持字母、数字、
点、下划线和连字符。完整键在当前已装配插件中需要保持唯一。回调运行在 Gateway 管理请求中，建议只替换本地状态。避免在回调里做阻塞 I/O、
远程调用或不可逆操作。只有当前已装配插件才会显示。新增、替换或删除插件 JAR 后，仍需要重启 Gateway。

## 4. 声明 SPI

在插件项目中创建以下文件之一（或同时创建两个）：

```text
src/main/resources/META-INF/services/com.rover.common.spi.filter.Filter
src/main/resources/META-INF/services/com.rover.common.spi.loadbalance.LoadBalancer
```

每个文件每行写一个实现类的全限定名。这里填写类名，不填写 `.class` 或 JAR 路径。例如：

```text
com.example.rover.RequestTagFilter
```

```text
com.example.rover.FirstHealthyLoadBalancer
```

文件名、包名和大小写需要完全匹配。你也可以不使用 SPI，而是在 `filters.classes` 中显式配置 Filter 的全限定类名。LoadBalancer 可以把 `gateway.loadbalance.strategy` 直接设为实现类全限定名。

若选择显式类名方式，可以不提供 SPI 文件。配置示例如下：

```yaml
rover:
  gateway:
    filters:
      enabled: true
      pluginDir: plugins
      classes:
        - org.example.rover.DemoBlockFilter
```

这个配置仍会扫描 `plugins/` 中的 JAR，只是不再通过 SPI 清单选类，而是按配置反射创建指定类。

## 5. 打包并放入 Gateway

```bash
mvn -DskipTests package
mkdir -p <gateway-working-dir>/plugins
cp target/my-rover-plugin-1.0.0.jar <gateway-working-dir>/plugins/
```

在 IntelliJ 的 Maven 面板执行 `package` 后，JAR 位于插件项目的 `target/` 下。将该 JAR 复制到 Gateway
的 `pluginDir`；默认值 `plugins` 表示 **Gateway 进程工作目录** 下的 `plugins/`，不是 Java 源码包，也不是
Admin 的目录。下图展示了已声明 SPI、执行 `package` 并生成 JAR 的完整对应关系。

![声明 SPI 并打包 Filter 插件](./images/plugin/filter-plugin-package-spi.png)

`pluginDir` 控制 Gateway 从哪里扫描插件 JAR。不配置时，默认使用 Gateway 进程工作目录下的 `plugins`。你也可以使用绝对路径或自定义相对路径：

```yaml
rover:
  gateway:
    filters:
      enabled: true
      pluginDir: plugins
      classes:
        - com.example.rover.RequestTagFilter
    loadbalance:
      strategy: first_healthy
```

`pluginDir` 是文件系统目录，不是 classpath 目录。相对路径按 Gateway 进程的当前工作目录解析。一个目录下的所有 `*.jar` 都会被扫描。建议一个插件版本使用唯一文件名，并清理旧版本，避免加载到重复类。

## 6. 验证是否接入成功

1. 启动 Gateway，检查日志中的 `Loaded ... plugin jar(s)`、`Discovered plugin filter via SPI` 或 `Gateway filter chain ready`。
2. `gateway.loadbalance.strategy` 控制 Gateway 当前使用哪一个负载均衡策略。它可以填写内置策略、LoadBalancer SPI `name()` 或实现类全限定名。该项属于启动/插件装配配置，不在 Admin 中修改。Admin 仪表盘只展示当前运行状态。
3. 多个策略可以共存，但一次只能选中一个全局策略。SPI 名称重复时，Gateway 会启动失败。这里的重复也包括和内置策略同名。
4. 对匹配路由发起一次请求，在请求追踪或业务日志中确认 Filter 已执行。对 LoadBalancer，可以观察请求是否按自定义策略选择实例。
5. 修改插件 JAR、SPI 文件或类路径后重启 Gateway。仅修改可热更新配置时，是否立即生效取决于对应配置项。新增或替换 JAR 不建议依赖热更新。

### 已验证的 Filter SPI 示例

以下本地演示完整验证了 Filter SPI 的两层结果：

1. Gateway 从 `E:\\roverSuite\\plugins` 发现一个 JAR，读取 SPI 声明，识别到
   `org.example.rover.DemoBlockFilter`，并将 `demo-block` 加入过滤器链，顺序为 `100`。
2. 真实请求到达后，日志输出“执行自定义 Filter”，说明它不仅被扫描到，而且确实参与了请求处理。

![Gateway 发现 JAR 并装配 Filter 链](./images/plugin/filter-plugin-gateway-loaded.png)

![真实请求执行自定义 Filter](./images/plugin/filter-plugin-request-executed.png)

对于 `DemoBlockFilter` 这种按请求头拒绝请求的插件，可进一步执行：

```powershell
curl.exe -i -H "X-Demo-Plugin: block" http://127.0.0.1:80/any-matched-route
```

预期返回 `418` 和 `blocked by demo plugin`。未携带该请求头时，插件会调用 `chain.doFilter(context)`。请求会继续交给后续路由与代理处理。

### 已验证的显式类名示例（无 SPI）

在移除 SPI 文件、先执行 Maven `clean` 再 `package` 以清除 `target/` 中的旧资源后，将无 SPI JAR 放回 `plugins/`。
配置 `filters.classes` 为 `org.example.rover.DemoBlockFilter` 并重启 Gateway，真实请求同样进入自定义 Filter。下图同时展示了
配置的完整类名和运行日志中的“执行自定义 Filter”。

![无 SPI 时由 filters.classes 显式加载 Filter](./images/plugin/filter-plugin-class-config-executed.png)

### 已验证的用户自定义限流 Filter 示例

Rover 的内置限流由 `RateLimitFilter` 提供，支持 `token_bucket` 和 `sliding_window` 两个内置算法。如果你要做 IP、用户、
租户、灰度分组或 Redis 分布式限流，通常不需要扩展内部 `RateLimiter`。直接写一个普通 `Filter` 插件会更清晰。

本地演示中的 `DemoRateLimitFilter` 使用显式类名方式装配：

```yaml
rover:
  gateway:
    filters:
      enabled: true
      pluginDir: plugins
      classes:
        - org.example.rover.DemoRateLimitFilter
```

启动日志中可以看到 `demo-rate-limit` 被装进过滤器链，真实连续请求超过阈值后返回 `429`。这证明用户自定义限流和普通
Filter 插件是同一条扩展路径。

![用户自定义限流 Filter 装配成功](./images/plugin/filter-plugin-rate-limit-loaded.png)

![用户自定义限流 Filter 返回 429](./images/plugin/filter-plugin-rate-limit-rejected.png)

### 已验证的 LoadBalancer 全限定类名示例

LoadBalancer 也可以不依赖 SPI 文件，直接把 `gateway.loadbalance.strategy` 写成实现类全限定名。下图使用
`org.example.rover.DemoHighestPortLoadBalancer` 验证：Gateway 从 `plugins/` 中加载 JAR，按类名创建自定义负载均衡器，并在真实请求中执行该策略。

![LoadBalancer 按全限定类名加载并参与请求](./images/plugin/loadbalancer-class-config-executed.png)

注意：`gateway.loadbalance.strategy` 属于启动/插件装配配置，不在 Admin 中修改；如需切换自定义 LoadBalancer，请改
`rover-gateway.yml` 并重启 Gateway。Admin 侧不管理这类配置，可以避免出现“YAML 写了自定义策略，但运行时被管理台改回内置策略”的误判。

对于上例的 `request-tag` 可配置插件，直接调用 Gateway 管理接口修改字段，效果与在 Admin 中保存一致：

```bash
curl -X POST http://127.0.0.1:8080/_manage/configs \
  -H 'Content-Type: application/json' \
  -d '{"key":"gateway.plugin.request-tag.enabled","value":"false"}'
```

下一次请求即可使用新值。`GET /_manage/configs` 会列出当前已装配插件实际声明的全部配置键。

## 7. 常见问题

- **没有发现插件**：确认目录存在、文件扩展名是 `.jar`，且目录按 Gateway 工作目录解析；检查 JAR 内确实包含 `META-INF/services/...`。
- **SPI 文件无效**：文件名需要是接口全限定名，内容需要是实现类全限定名；每行一个类名，不填写 Markdown、逗号或额外后缀。
- **显式类名加载失败**：确认类是 `public`、有无参构造函数，并且实现了对应接口；这里需要填写完整类名，不填写简单类名。
- **`strategy` 不支持**：SPI 模式下使用实现类的 `name()`，并确保配置值大小写与名称一致（匹配本身不区分大小写）；也可以直接填写包含 `.` 的全限定类名。
- **`NoSuchMethod` 或 `ClassCastException`**：插件编译时使用的 `rover-common` 与 Gateway 版本不一致，或把 `rover-common` 重复打进了插件 JAR。
- **插件是否会拖慢 Gateway**：简单判断请求头、写请求属性、内存计数或选择上游这类插件通常开销很小；真正的风险来自插件代码在请求链路里做阻塞 I/O、无超时远程调用、无限重试或大对象日志。如果确实要访问外部系统，请设置超时、限流和故障降级。

## 8. 安全与兼容性边界

插件拥有 Gateway 进程权限，加载前建议把 JAR 当作受信任代码审查。当前没有独立沙箱、签名校验或租户隔离。日志、属性或异常中建议避免写入 token、Cookie、Authorization 和完整请求体。

插件建议锁定与 Gateway 相同的 Rover-Suite 版本，并在升级时重新编译和验证。SPI 接口、配置键和 `ServiceInstance`/`RequestContext` 契约属于扩展边界。未文档化的内部类不保证兼容。发布插件时建议同时提供源码、版本号、依赖清单和兼容矩阵。
