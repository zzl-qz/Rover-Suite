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

`rover-gateway-adapter-nacos` 当前是保留的适配器骨架，尚未实现可用的 Nacos 服务发现运行时集成。当前可用发现模式是内置 Nameserver 和静态上游；适配器完成前请使用外部适配层或自行实现
`ServiceDiscovery` 扩展。
