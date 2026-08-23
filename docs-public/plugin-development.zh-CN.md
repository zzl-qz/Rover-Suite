# 插件开发与接入

Rover Gateway 的插件运行在 Gateway 进程内，用于补充请求过滤器（Filter）或负载均衡策略（LoadBalancer）。插件不是独立服务，也不是 Admin 插件：它会直接参与网关请求链路，必须和当前 Rover-Suite 版本保持兼容。

本指南覆盖从创建 JAR、声明 SPI、放入 Gateway，到配置和验证的完整流程。真正可编译的完整 Demo 可在此基础上单独维护；发布插件前请先在与目标环境一致的 JDK 和 Rover-Suite 版本上验证。

## 挂载方式总览

当前可直接把 JAR 放入 Gateway `plugins` 目录的插件类型有 **2 种**，对应 **4 条挂载路径**：

| 插件类型 | SPI 自动发现 | 显式类名加载 |
| --- | --- | --- |
| `Filter` | `META-INF/services/com.rover.common.spi.filter.Filter`，加载后按 `getOrder()` 组装过滤器链 | `rover.gateway.filters.classes` 填 Filter 全限定类名 |
| `LoadBalancer` | `META-INF/services/com.rover.common.spi.loadbalance.LoadBalancer`，通过 `name()` 配置选择 | `rover.gateway.loadbalance.strategy` 直接填写实现类全限定名 |

这 4 条路径共用同一个 `pluginDir` 和 `URLClassLoader`。`ServiceDiscovery`、注册传输适配器和 Nacos 当前属于源码级扩展，
不会通过 `plugins` 目录直接挂载；EventBus 的 `ServiceLoader` 也不是本目录插件机制。

## 1. 选择扩展点

| 扩展点 | 用途 | 接入方式 |
| --- | --- | --- |
| `com.rover.common.spi.filter.Filter` | 在请求转发前后做鉴权、灰度标记、审计等轻量处理 | SPI 自动发现，或在 `filters.classes` 中填写全限定类名 |
| `com.rover.common.spi.loadbalance.LoadBalancer` | 从健康实例列表中选择一个上游实例 | SPI `name()` 选择，或直接填写实现类全限定名 |

Filter 的 `getOrder()` 越小越早执行。过滤器继续执行后续链路时必须调用 `chain.doFilter(context)`；如果自己已经写回响应，则调用 `context.markCompleted()` 并返回已完成的 Future。

## 2. 创建最小 Maven 插件项目

插件项目只需要编译期依赖 `rover-common`。运行时 Gateway 已提供该依赖，因此不要把 `rover-common` 或整套 Gateway 重新打进插件 JAR，也不要使用 shade 把 Rover 类复制一份。

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

实现类必须是 `public`、可实例化，并提供无参构造函数。LoadBalancer 只会收到调用方已经筛选出的候选实例；没有实例时应返回 `null`，不要在插件中自行查询注册中心。

## 4. 声明 SPI

在插件项目中创建以下文件之一（或同时创建两个）：

```text
src/main/resources/META-INF/services/com.rover.common.spi.filter.Filter
src/main/resources/META-INF/services/com.rover.common.spi.loadbalance.LoadBalancer
```

每个文件每行写一个实现类的全限定名，不要写 `.class` 或 JAR 路径。例如：

```text
com.example.rover.RequestTagFilter
```

```text
com.example.rover.FirstHealthyLoadBalancer
```

文件名、包名和大小写必须完全匹配。也可以不使用 SPI，而在 `filters.classes` 中显式配置 Filter 的全限定类名；LoadBalancer 则可把 `gateway.loadbalance.strategy` 直接设为实现类全限定名。

## 5. 打包并放入 Gateway

```bash
mvn -DskipTests package
mkdir -p <gateway-working-dir>/plugins
cp target/my-rover-plugin-1.0.0.jar <gateway-working-dir>/plugins/
```

默认插件目录是 Gateway 进程工作目录下的 `plugins`。也可以使用绝对路径或自定义相对路径：

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

`pluginDir` 是文件系统目录，不是 classpath 目录；相对路径按 Gateway 进程的当前工作目录解析。一个目录下的所有 `*.jar` 都会被扫描，建议一个插件版本使用唯一文件名，并清理旧版本，避免加载到重复类。

## 6. 验证是否接入成功

1. 启动 Gateway，检查日志中的 `Loaded ... plugin jar(s)`、`Discovered plugin filter via SPI` 或 `Gateway filter chain ready`。
2. 在 Admin 的配置管理中确认 `gateway.filters.enabled`、`gateway.filters.pluginDir` 和 `gateway.loadbalance.strategy` 的实际值。
3. 对匹配路由发起一次请求，在请求追踪或业务日志中确认 Filter 已执行；对 LoadBalancer 观察请求是否按自定义策略选择实例。
4. 修改插件 JAR、SPI 文件或类路径后重启 Gateway。仅修改可热更新配置时，是否立即生效取决于对应配置项；新增或替换 JAR 不应依赖热更新。

## 7. 常见问题

- **没有发现插件**：确认目录存在、文件扩展名是 `.jar`，且目录按 Gateway 工作目录解析；检查 JAR 内确实包含 `META-INF/services/...`。
- **SPI 文件无效**：文件名必须是接口全限定名，内容必须是实现类全限定名；每行一个类名，不要写 Markdown、逗号或额外后缀。
- **显式类名加载失败**：确认类是 `public`、有无参构造函数，并且实现了对应接口；不要只填写简单类名。
- **`strategy` 不支持**：SPI 模式下使用实现类的 `name()`，并确保配置值大小写与名称一致（匹配本身不区分大小写）；也可以直接填写包含 `.` 的全限定类名。
- **`NoSuchMethod` 或 `ClassCastException`**：插件编译时使用的 `rover-common` 与 Gateway 版本不一致，或把 `rover-common` 重复打进了插件 JAR。
- **启动后请求变慢或不稳定**：插件在网关 JVM 内执行，禁止在 Netty 事件循环上做阻塞 I/O、远程调用、无限重试或大对象日志；外部调用应设置超时、限流和故障降级。

## 8. 安全与兼容性边界

插件拥有 Gateway 进程权限，加载前请把 JAR 当作受信任代码审查；当前没有独立沙箱、签名校验或租户隔离。不要在日志、属性或异常中写入 token、Cookie、Authorization 和完整请求体。

插件应锁定与 Gateway 相同的 Rover-Suite 版本，并在升级时重新编译和验证。SPI 接口、配置键和 `ServiceInstance`/`RequestContext` 契约属于扩展边界；未文档化的内部类不保证兼容。发布插件时建议同时提供源码、版本号、依赖清单和兼容矩阵。
