# 升级、兼容性与扩展

## 版本策略

- 发布版本使用 SemVer；破坏协议/API 的变更进入主版本，兼容功能进入次版本，修复进入补丁版本。
- `SNAPSHOT` 只用于开发，不保证升级兼容。
- 发布时为源码创建同名 Git tag，并附 JAR、校验文件、依赖树和变更记录。
- 升级前备份路由 overlay 和 runtime overlay；升级后检查管理 API 与协议 token。

## 兼容边界

- Nameserver TCP 注册/订阅协议和 HTTP Registration API 是跨版本重点兼容面。
- Admin API 属于同版本控制面，客户端不建议依赖未文档化字段。
- 配置键新增通常兼容；删除、改类型或改变默认值需要记录在 Changelog。
- `rover.gateway.proxy.outbound` 与 `rover.gateway.server.ioTransport` 在启动时选定，热更新不会切换出站客户端或 I/O 实现。改完后重启 Gateway。当前实现可从启动日志里的 `outbound=` / `ioTransport=` 核对。

## 插件开发

Gateway 支持 Filter 和 LoadBalancer 扩展。完整的项目结构、SPI 文件、打包、配置、验证和排错步骤见
[插件开发与接入](./plugin-development.zh-CN.md)。

```java
public final class ExampleLoadBalancer implements LoadBalancer {
    @Override public String name() { return "example"; }
    // 按 LoadBalancer 接口实现 choose(...)
}
```

插件运行在 Gateway 进程内，需要避免阻塞 Netty 事件循环、泄漏线程/连接或记录敏感请求数据。

## Nacos 适配器状态

`rover-gateway-adapter-nacos` 现在提供可选的 Nacos 服务发现能力。它不是独立进程：用户可以自行添加 adapter 依赖，也可以使用 Maven 的 `nacos` profile 构建带 Nacos 的 Gateway。Nacos Config 和 Nacos 服务注册不在本适配器范围内。
