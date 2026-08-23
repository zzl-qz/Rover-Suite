# Plugin Development and Integration

Rover Gateway plugins run inside the Gateway process and extend either request filtering (`Filter`) or upstream selection (`LoadBalancer`). They are not standalone services and are not Admin plugins: they participate directly in the Gateway request path and must match the Rover-Suite version.

This guide covers creating a JAR, declaring SPI metadata, placing it in Gateway, configuring it, and verifying it. A complete compilable demo can be maintained separately; before publishing a plugin, validate it with the same JDK and Rover-Suite version as the target deployment.

## Mounting methods at a glance

There are **2 directly mountable plugin types** and **4 mounting paths**:

| Plugin type | SPI auto-discovery | Explicit class loading |
| --- | --- | --- |
| `Filter` | `META-INF/services/com.rover.common.spi.filter.Filter`, then ordered by `getOrder()` | Put the Filter FQCN in `rover.gateway.filters.classes` |
| `LoadBalancer` | `META-INF/services/com.rover.common.spi.loadbalance.LoadBalancer`, selected by `name()` | Put the implementation FQCN in `rover.gateway.loadbalance.strategy` |

All four paths share the same `pluginDir` and `URLClassLoader`. `ServiceDiscovery`,
registration transport adapters, and Nacos are source-level extensions today and
are not mounted from `plugins`; the EventBus `ServiceLoader` is also a separate
classpath mechanism, not a `plugins`-directory plugin.

## 1. Choose an extension point

| Extension point | Use | Integration |
| --- | --- | --- |
| `com.rover.common.spi.filter.Filter` | Lightweight auth, traffic labels, audit, and request policy | Auto-discovered through SPI, or listed by FQCN in `filters.classes` |
| `com.rover.common.spi.loadbalance.LoadBalancer` | Select one upstream from the healthy candidate list | Select by SPI `name()`, or configure the implementation FQCN |

Lower `Filter.getOrder()` values run earlier. A filter must call `chain.doFilter(context)` to continue. If it writes the response itself, call `context.markCompleted()` and return an already-completed future.

## 2. Create a minimal Maven plugin project

The plugin only needs a compile-time dependency on `rover-common`. Gateway provides it at runtime, so do not package `rover-common` or the whole Gateway into the plugin JAR, and do not use a shade plugin to duplicate Rover classes.

```xml
<dependency>
  <groupId>com.rover</groupId>
  <artifactId>rover-common</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <scope>provided</scope>
</dependency>
```

If this snapshot is not available from a Maven repository, run `mvn -DskipTests install` in the Rover-Suite root first, or publish `rover-common` to an internal repository. Compile for a JDK supported by Gateway (JDK 17+ for the current project).

## 3. Implement the plugin

### Filter example

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

### LoadBalancer example

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

Implementations must be public, instantiable, and have a no-argument constructor. A LoadBalancer receives candidates that the caller has already filtered for health. Return `null` when there are no candidates; do not query Nameserver from the plugin.

## 4. Declare SPI metadata

Create one or both of these files in the plugin project:

```text
src/main/resources/META-INF/services/com.rover.common.spi.filter.Filter
src/main/resources/META-INF/services/com.rover.common.spi.loadbalance.LoadBalancer
```

Each file contains one implementation FQCN per line, without `.class` or a JAR path:

```text
com.example.rover.RequestTagFilter
```

```text
com.example.rover.FirstHealthyLoadBalancer
```

File names, packages, and case must match exactly. SPI is optional for filters: you can instead list a filter FQCN in `filters.classes`. For a LoadBalancer, set `gateway.loadbalance.strategy` to the SPI `name()` or directly to an implementation FQCN.

## 5. Package and install it in Gateway

```bash
mvn -DskipTests package
mkdir -p <gateway-working-dir>/plugins
cp target/my-rover-plugin-1.0.0.jar <gateway-working-dir>/plugins/
```

The default plugin directory is `plugins` under the Gateway process working directory. Use an absolute or custom relative path if needed:

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

`pluginDir` is a filesystem directory, not a classpath directory. Relative paths are resolved from the Gateway process working directory. Every `*.jar` in the directory is scanned; use unique filenames and remove old versions to avoid duplicate classes.

## 6. Verify the integration

1. Start Gateway and check for `Loaded ... plugin jar(s)`, `Discovered plugin filter via SPI`, or `Gateway filter chain ready` in the logs.
2. In the Admin configuration page, verify the effective values of `gateway.filters.enabled`, `gateway.filters.pluginDir`, and `gateway.loadbalance.strategy`.
3. Send a request through a matching route and confirm the filter in tracing or application logs; for a LoadBalancer, observe that requests follow the custom selection policy.
4. Restart Gateway after changing a plugin JAR, SPI file, or class path. Whether a configuration-only change is hot-applied depends on that configuration item; do not rely on hot reload for adding or replacing JARs.

## 7. Troubleshooting

- **Plugin is not discovered:** check that the directory exists, the file ends in `.jar`, the path is resolved from Gateway's working directory, and the JAR contains the expected `META-INF/services/...` file.
- **Invalid SPI file:** the file name must be the interface FQCN and each line must be an implementation FQCN; do not add Markdown, commas, or suffixes.
- **Explicit class cannot be loaded:** the class must be public, have a no-argument constructor, implement the expected interface, and be configured with its FQCN.
- **Unsupported `strategy`:** with SPI, use the implementation's `name()` and make the configured value match it (matching is case-insensitive); an FQCN containing `.` is also accepted.
- **`NoSuchMethod` or `ClassCastException`:** the plugin was compiled against a different `rover-common` version, or bundled a duplicate copy of `rover-common`.
- **Requests become slow or unstable:** plugins execute in the Gateway JVM. Never perform blocking I/O, remote calls, unbounded retries, or large-body logging on the Netty event loop; use timeouts, limits, and fallbacks for external calls.

## 8. Security and compatibility boundaries

A plugin has Gateway process privileges. Review JARs as trusted code; there is currently no sandbox, signature verification, or tenant isolation. Never put tokens, cookies, Authorization headers, or complete request bodies in logs, attributes, or exceptions.

Pin the plugin to the same Rover-Suite version as Gateway and rebuild and verify it during upgrades. SPI interfaces, documented configuration keys, and the `ServiceInstance`/`RequestContext` contracts are extension boundaries; undocumented internal classes are not compatibility promises. Publish source, version, dependency inventory, and a compatibility matrix with the plugin.
